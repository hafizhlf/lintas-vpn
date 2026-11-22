use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};

use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong, jlongArray};
use jni::JNIEnv;

mod masque;

pub(crate) struct Stats {
    pub(crate) packets: AtomicU64,
    pub(crate) bytes: AtomicU64,
    pub(crate) net_rx_packets: AtomicU64,
    pub(crate) net_tx_packets: AtomicU64,
}

struct Handle {
    stop: Arc<AtomicBool>,
    stats: Arc<Stats>,
    thread: Option<JoinHandle<()>>,
}

fn packet_loop(fd: i32, stop: Arc<AtomicBool>, stats: Arc<Stats>) {
    let mut buffer = vec![0u8; 65_536];
    let mut poll_fd = libc::pollfd {
        fd,
        events: libc::POLLIN,
        revents: 0,
    };

    while !stop.load(Ordering::Relaxed) {
        let ready = unsafe { libc::poll(&mut poll_fd, 1, 500) };
        if ready <= 0 {
            continue;
        }
        if poll_fd.revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
            break;
        }
        if poll_fd.revents & libc::POLLIN == 0 {
            continue;
        }

        let read = unsafe {
            libc::read(
                fd,
                buffer.as_mut_ptr() as *mut libc::c_void,
                buffer.len(),
            )
        };

        if read > 0 {
            stats.packets.fetch_add(1, Ordering::Relaxed);
            stats.bytes.fetch_add(read as u64, Ordering::Relaxed);
        } else if read < 0 {
            let errno = std::io::Error::last_os_error();
            match errno.raw_os_error() {
                Some(libc::EINTR) | Some(libc::EAGAIN) => continue,
                _ => break,
            }
        }
    }
}

fn wireguard_loop(
    tun_fd: i32,
    udp_fd: i32,
    mut tunn: Tunn,
    reserved: [u8; 3],
    peer: SocketAddr,
    stop: Arc<AtomicBool>,
    stats: Arc<Stats>,
) {
    let mut tun_buf = vec![0u8; 65_536];
    let mut udp_buf = vec![0u8; 65_536];
    let mut out = vec![0u8; 65_536 + 64];

    let mut poll_fds = [
        libc::pollfd {
            fd: tun_fd,
            events: libc::POLLIN,
            revents: 0,
        },
        libc::pollfd {
            fd: udp_fd,
            events: libc::POLLIN,
            revents: 0,
        },
    ];

    while !stop.load(Ordering::Relaxed) {
        let ready = unsafe { libc::poll(poll_fds.as_mut_ptr(), 2, 250) };

        if ready < 0 {
            let errno = std::io::Error::last_os_error();
            if errno.raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            break;
        }

        if ready > 0 {
            if poll_fds[0].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
                break;
            }
            if poll_fds[1].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
                break;
            }

            if poll_fds[0].revents & libc::POLLIN != 0 {
                let read = unsafe {
                    libc::read(
                        tun_fd,
                        tun_buf.as_mut_ptr() as *mut libc::c_void,
                        tun_buf.len(),
                    )
                };
                if read > 0 {
                    stats.packets.fetch_add(1, Ordering::Relaxed);
                    stats.bytes.fetch_add(read as u64, Ordering::Relaxed);

                    match tunn.encapsulate(&tun_buf[..read as usize], &mut out) {
                        TunnResult::WriteToNetwork(packet) => {
                            inject_client_id(packet, reserved);
                            udp_send(udp_fd, packet, peer, &stats);
                        }
                        TunnResult::WriteToTunnelV4(packet, _) => {
                            tun_write(tun_fd, packet);
                        }
                        TunnResult::WriteToTunnelV6(packet, _) => {
                            tun_write(tun_fd, packet);
                        }
                        _ => {}
                    }
                }
            }

            if poll_fds[1].revents & libc::POLLIN != 0 {
                let read = unsafe {
                    libc::recv(
                        udp_fd,
                        udp_buf.as_mut_ptr() as *mut libc::c_void,
                        udp_buf.len(),
                        0,
                    )
                };
                if read > 0 {
                    stats.net_rx_packets.fetch_add(1, Ordering::Relaxed);
                    strip_client_id(&mut udp_buf[..read as usize]);

                    let mut datagram: &[u8] = &udp_buf[..read as usize];
                    loop {
                        match tunn.decapsulate(None, datagram, &mut out) {
                            TunnResult::Done => break,
                            TunnResult::Err(_) => break,
                            TunnResult::WriteToNetwork(packet) => {
                                inject_client_id(packet, reserved);
                                udp_send(udp_fd, packet, peer, &stats);
                                datagram = &[];
                            }
                            TunnResult::WriteToTunnelV4(packet, _) => {
                                tun_write(tun_fd, packet);
                                datagram = &[];
                            }
                            TunnResult::WriteToTunnelV6(packet, _) => {
                                tun_write(tun_fd, packet);
                                datagram = &[];
                            }
                        }
                    }
                }
            }
        }

        match tunn.update_timers(&mut out) {
            TunnResult::WriteToNetwork(packet) => {
                inject_client_id(packet, reserved);
                udp_send(udp_fd, packet, peer, &stats);
            }
            TunnResult::WriteToTunnelV4(packet, _) => {
                tun_write(tun_fd, packet);
            }
            TunnResult::WriteToTunnelV6(packet, _) => {
                tun_write(tun_fd, packet);
            }
            _ => {}
        }
    }
}

fn udp_send(fd: i32, packet: &[u8], peer: SocketAddr, stats: &Stats) {
    let sent = match peer {
        SocketAddr::V4(address) => {
            let sockaddr = libc::sockaddr_in {
                sin_family: libc::AF_INET as libc::sa_family_t,
                sin_port: address.port().to_be(),
                sin_addr: libc::in_addr {
                    s_addr: u32::from_ne_bytes(address.ip().octets()),
                },
                sin_zero: [0; 8],
            };
            unsafe {
                libc::sendto(
                    fd,
                    packet.as_ptr() as *const libc::c_void,
                    packet.len(),
                    0,
                    &sockaddr as *const libc::sockaddr_in as *const libc::sockaddr,
                    std::mem::size_of::<libc::sockaddr_in>() as libc::socklen_t,
                )
            }
        }
        SocketAddr::V6(address) => {
            let sockaddr = libc::sockaddr_in6 {
                sin6_family: libc::AF_INET6 as libc::sa_family_t,
                sin6_port: address.port().to_be(),
                sin6_flowinfo: 0,
                sin6_addr: libc::in6_addr {
                    s6_addr: address.ip().octets(),
                },
                sin6_scope_id: 0,
            };
            unsafe {
                libc::sendto(
                    fd,
                    packet.as_ptr() as *const libc::c_void,
                    packet.len(),
                    0,
                    &sockaddr as *const libc::sockaddr_in6 as *const libc::sockaddr,
                    std::mem::size_of::<libc::sockaddr_in6>() as libc::socklen_t,
                )
            }
        }
    };
    if sent > 0 {
        stats.net_tx_packets.fetch_add(1, Ordering::Relaxed);
    }
}

fn tun_write(fd: i32, packet: &[u8]) {
    unsafe {
        libc::write(
            fd,
            packet.as_ptr() as *const libc::c_void,
            packet.len(),
        );
    }
}

fn inject_client_id(packet: &mut [u8], reserved: [u8; 3]) {
    if packet.len() >= 4 && (1..=4).contains(&packet[0]) {
        packet[1..4].copy_from_slice(&reserved);
    }
}

fn strip_client_id(packet: &mut [u8]) {
    if packet.len() >= 4 && (1..=4).contains(&packet[0]) {
        packet[1..4].fill(0);
    }
}

#[cfg(target_os = "android")]
#[link(name = "log")]
extern "C" {
    fn __android_log_write(
        prio: i32,
        tag: *const std::os::raw::c_char,
        text: *const std::os::raw::c_char,
    ) -> i32;
}

fn logcat(message: &str) {
    #[cfg(target_os = "android")]
    {
        if let Ok(text) = std::ffi::CString::new(message) {
            unsafe {
                __android_log_write(
                    3,
                    b"LintasCore\0".as_ptr() as *const std::os::raw::c_char,
                    text.as_ptr(),
                );
            }
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = message;
    }
}

fn read_string(env: &mut JNIEnv, value: JString) -> Option<String> {
    env.get_string(&value).ok().map(|text| text.into())
}

fn read_bytes(env: &JNIEnv, array: JByteArray) -> Vec<u8> {
    let length = env.get_array_length(&array).unwrap_or(0) as usize;
    if length == 0 {
        return Vec::new();
    }
    let mut buffer = vec![0i8; length];
    if env
        .get_byte_array_region(&array, 0, &mut buffer)
        .is_err()
    {
        return Vec::new();
    }
    buffer.into_iter().map(|value| value as u8).collect()
}

#[no_mangle]
pub extern "system" fn Java_app_lintas_core_NativeCore_start(
    _env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
) -> jlong {
    if tun_fd < 0 {
        return 0;
    }

    let stop = Arc::new(AtomicBool::new(false));
    let stats = Arc::new(Stats {
        packets: AtomicU64::new(0),
        bytes: AtomicU64::new(0),
        net_rx_packets: AtomicU64::new(0),
        net_tx_packets: AtomicU64::new(0),
    });

    let thread_stop = stop.clone();
    let thread_stats = stats.clone();
    let spawned = thread::Builder::new()
        .name("lintas-packet-loop".to_string())
        .spawn(move || packet_loop(tun_fd, thread_stop, thread_stats));

    match spawned {
        Ok(thread) => Box::into_raw(Box::new(Handle {
            stop,
            stats,
            thread: Some(thread),
        })) as jlong,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_lintas_core_NativeCore_startWireGuard(
    mut env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
    udp_fd: jint,
    private_key: JByteArray,
    peer_public_key: JByteArray,
    reserved: JByteArray,
    peer_address: JString,
    peer_port: jint,
) -> jlong {
    if tun_fd < 0 || udp_fd < 0 {
        return 0;
    }

    let private_bytes = read_bytes(&env, private_key);
    let peer_bytes = read_bytes(&env, peer_public_key);
    let reserved_bytes = read_bytes(&env, reserved);

    if private_bytes.len() != 32 || peer_bytes.len() != 32 {
        return 0;
    }

    let peer_host: String = match env.get_string(&peer_address) {
        Ok(value) => value.into(),
        Err(_) => return 0,
    };
    let peer_ip: IpAddr = match peer_host.parse() {
        Ok(value) => value,
        Err(_) => return 0,
    };
    let peer = SocketAddr::new(peer_ip, peer_port as u16);

    let mut private_key_array = [0u8; 32];
    private_key_array.copy_from_slice(&private_bytes);
    let mut peer_key_array = [0u8; 32];
    peer_key_array.copy_from_slice(&peer_bytes);
    let mut reserved_array = [0u8; 3];
    if reserved_bytes.len() == 3 {
        reserved_array.copy_from_slice(&reserved_bytes);
    }

    let tunn = Tunn::new(
        StaticSecret::from(private_key_array),
        PublicKey::from(peer_key_array),
        None,
        Some(25),
        0,
        None,
    );

    let stop = Arc::new(AtomicBool::new(false));
    let stats = Arc::new(Stats {
        packets: AtomicU64::new(0),
        bytes: AtomicU64::new(0),
        net_rx_packets: AtomicU64::new(0),
        net_tx_packets: AtomicU64::new(0),
    });

    let thread_stop = stop.clone();
    let thread_stats = stats.clone();
    let spawned = thread::Builder::new()
        .name("lintas-wireguard".to_string())
        .spawn(move || wireguard_loop(tun_fd, udp_fd, tunn, reserved_array, peer, thread_stop, thread_stats));

    match spawned {
        Ok(thread) => Box::into_raw(Box::new(Handle {
            stop,
            stats,
            thread: Some(thread),
        })) as jlong,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_lintas_core_NativeCore_startMasque(
    mut env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
    udp_fd: jint,
    cert_path: JString,
    key_path: JString,
    peer_address: JString,
    peer_port: jint,
    local_address: JString,
    local_port: jint,
    sni: JString,
    endpoint_pub_key_pem: JString,
) -> jlong {
    if tun_fd < 0 || udp_fd < 0 {
        return 0;
    }

    let Some(cert_path) = read_string(&mut env, cert_path) else { return 0; };
    let Some(key_path) = read_string(&mut env, key_path) else { return 0; };
    let Some(peer_host) = read_string(&mut env, peer_address) else { return 0; };
    let Some(local_host) = read_string(&mut env, local_address) else { return 0; };
    let Some(sni) = read_string(&mut env, sni) else { return 0; };
    let endpoint_pub_key_pem = read_string(&mut env, endpoint_pub_key_pem)
        .filter(|value| !value.trim().is_empty());

    let peer_ip: IpAddr = match peer_host.parse() {
        Ok(value) => value,
        Err(_) => return 0,
    };
    let local_ip: IpAddr = match local_host.parse() {
        Ok(value) => value,
        Err(_) => return 0,
    };
    let peer = SocketAddr::new(peer_ip, peer_port as u16);
    let local = SocketAddr::new(local_ip, local_port as u16);

    let config = masque::MasqueConfig {
        tun_fd,
        udp_fd,
        cert_path,
        key_path,
        peer,
        local,
        sni,
        endpoint_pub_key_pem,
    };

    let stop = Arc::new(AtomicBool::new(false));
    let stats = Arc::new(Stats {
        packets: AtomicU64::new(0),
        bytes: AtomicU64::new(0),
        net_rx_packets: AtomicU64::new(0),
        net_tx_packets: AtomicU64::new(0),
    });

    let thread_stop = stop.clone();
    let thread_stats = stats.clone();
    let spawned = thread::Builder::new()
        .name("lintas-masque".to_string())
        .spawn(move || {
            if let Err(error) = masque::run(config, thread_stop, thread_stats) {
                logcat(&format!("masque: {error:?}"));
            }
        });

    match spawned {
        Ok(thread) => Box::into_raw(Box::new(Handle {
            stop,
            stats,
            thread: Some(thread),
        })) as jlong,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_lintas_core_NativeCore_stop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }

    let mut boxed = unsafe { Box::from_raw(handle as *mut Handle) };
    boxed.stop.store(true, Ordering::Relaxed);
    if let Some(thread) = boxed.thread.take() {
        let _ = thread.join();
    }
    std::mem::forget(boxed);
}

#[no_mangle]
pub extern "system" fn Java_app_lintas_core_NativeCore_stats(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlongArray {
    let (packets, bytes, net_rx, net_tx) = if handle == 0 {
        (0, 0, 0, 0)
    } else {
        let state = unsafe { &*(handle as *const Handle) };
        (
            state.stats.packets.load(Ordering::Relaxed),
            state.stats.bytes.load(Ordering::Relaxed),
            state.stats.net_rx_packets.load(Ordering::Relaxed),
            state.stats.net_tx_packets.load(Ordering::Relaxed),
        )
    };

    match env.new_long_array(4) {
        Ok(array) => {
            let values = [
                packets as jlong,
                bytes as jlong,
                net_rx as jlong,
                net_tx as jlong,
            ];
            if env.set_long_array_region(&array, 0, &values).is_err() {
                return std::ptr::null_mut();
            }
            array.into_raw() as jlongArray
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wireguard_handshake_and_packet_roundtrip() {
        let a_secret = StaticSecret::from([1u8; 32]);
        let b_secret = StaticSecret::from([2u8; 32]);
        let a_public = PublicKey::from(&a_secret);
        let b_public = PublicKey::from(&b_secret);

        let mut a = Tunn::new(a_secret, b_public, None, None, 0, None);
        let mut b = Tunn::new(b_secret, a_public, None, None, 1, None);

        let packet = [
            0x45, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x40, 0x01,
            0x00, 0x00, 0x0a, 0x00, 0x00, 0x01, 0x0a, 0x00, 0x00, 0x02,
        ];
        let mut a_out = vec![0u8; 2048];
        let mut b_out = vec![0u8; 2048];

        let init = match a.encapsulate(&packet, &mut a_out) {
            TunnResult::WriteToNetwork(p) => p.to_vec(),
            _ => panic!("expected handshake initiation"),
        };

        let response = match b.decapsulate(None, &init, &mut b_out) {
            TunnResult::WriteToNetwork(p) => p.to_vec(),
            _ => panic!("expected handshake response"),
        };
        assert_eq!(response[0], 2);

        let _ = a.decapsulate(None, &response, &mut a_out);
        let data = loop {
            match a.decapsulate(None, &[], &mut a_out) {
                TunnResult::WriteToNetwork(p) => break p.to_vec(),
                TunnResult::Done => panic!("queued packet was not flushed"),
                TunnResult::Err(e) => panic!("unexpected error: {e:?}"),
                _ => panic!("unexpected result while flushing"),
            }
        };
        assert_eq!(data[0], 4);

        match b.decapsulate(None, &data, &mut b_out) {
            TunnResult::WriteToTunnelV4(p, _) | TunnResult::WriteToTunnelV6(p, _) => {
                assert_eq!(p, packet);
            }
            _ => panic!("expected packet to be written to tunnel"),
        }
    }
}
