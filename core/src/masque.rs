use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{bail, Result};
use ring::rand::SecureRandom;
use octets::{Octets, OctetsMut};
use quiche::h3::NameValue;

use crate::Stats;

const MAX_DATAGRAM_SIZE: usize = 1350;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(15);
const TUN_BUFFER: usize = 2048;
const UDP_BUFFER: usize = 65_535;

pub struct MasqueConfig {
    pub tun_fd: i32,
    pub udp_fd: i32,
    pub cert_path: String,
    pub key_path: String,
    pub peer: SocketAddr,
    pub local: SocketAddr,
    pub sni: String,
    pub endpoint_pub_key_pem: Option<String>,
}

pub fn run(config: MasqueConfig, stop: Arc<AtomicBool>, stats: Arc<Stats>) -> Result<()> {
    let mut quic_config = quiche::Config::new(quiche::PROTOCOL_VERSION)
        .map_err(|e| anyhow::anyhow!("quiche config: {e}"))?;
    quic_config.verify_peer(false);
    quic_config
        .set_application_protos(quiche::h3::APPLICATION_PROTOCOL)
        .map_err(|e| anyhow::anyhow!("set ALPN: {e}"))?;
    quic_config
        .load_cert_chain_from_pem_file(&config.cert_path)
        .map_err(|e| anyhow::anyhow!("load cert: {e}"))?;
    quic_config
        .load_priv_key_from_pem_file(&config.key_path)
        .map_err(|e| anyhow::anyhow!("load key: {e}"))?;
    quic_config.set_max_idle_timeout(0);
    quic_config.set_max_recv_udp_payload_size(MAX_DATAGRAM_SIZE);
    quic_config.set_max_send_udp_payload_size(MAX_DATAGRAM_SIZE);
    quic_config.set_initial_max_data(10_000_000);
    quic_config.set_initial_max_stream_data_bidi_local(1_000_000);
    quic_config.set_initial_max_stream_data_bidi_remote(1_000_000);
    quic_config.set_initial_max_stream_data_uni(1_000_000);
    quic_config.set_initial_max_streams_bidi(100);
    quic_config.set_initial_max_streams_uni(100);
    quic_config.set_disable_active_migration(true);
    quic_config.enable_dgram(true, 1000, 1000);

    let mut scid_bytes = [0u8; quiche::MAX_CONN_ID_LEN];
    ring::rand::SystemRandom::new()
        .fill(&mut scid_bytes)
        .map_err(|_| anyhow::anyhow!("RNG failure"))?;
    let scid = quiche::ConnectionId::from_ref(&scid_bytes);

    let mut conn = quiche::connect(
        Some(&config.sni),
        &scid,
        config.local,
        config.peer,
        &mut quic_config,
    )
    .map_err(|e| anyhow::anyhow!("quiche connect: {e}"))?;

    let mut out = vec![0u8; MAX_DATAGRAM_SIZE];
    let mut in_buf = vec![0u8; UDP_BUFFER];
    let mut tun_buf = vec![0u8; TUN_BUFFER];

    let handshake_deadline = Instant::now() + HANDSHAKE_TIMEOUT;
    flush(&mut conn, config.udp_fd, config.peer, &mut out)?;

    while !conn.is_established() {
        if stop.load(Ordering::Relaxed) {
            bail!("stopped");
        }
        if Instant::now() > handshake_deadline {
            bail!("MASQUE handshake timeout");
        }
        let wait = conn
            .timeout()
            .unwrap_or(Duration::from_millis(250))
            .min(Duration::from_millis(250));
        if wait_readable(config.udp_fd, wait) {
            if let Ok(n) = recv_into(config.udp_fd, &mut in_buf) {
                let recv_info = quiche::RecvInfo {
                    to: config.local,
                    from: config.peer,
                };
                let _ = conn.recv(&mut in_buf[..n], recv_info);
            }
        }
        conn.on_timeout();
        flush(&mut conn, config.udp_fd, config.peer, &mut out)?;
        if conn.is_closed() {
            bail!("QUIC closed during handshake");
        }
    }

    if let Some(expected_pem) = &config.endpoint_pub_key_pem {
        if expected_pem.trim().is_empty() {
            // no pin configured
        } else if let Some(cert_der) = conn.peer_cert() {
            let (_, cert) = x509_parser::parse_x509_certificate(cert_der)
                .map_err(|e| anyhow::anyhow!("parse peer cert: {e}"))?;
            let expected = pem::parse(expected_pem)
                .map_err(|e| anyhow::anyhow!("parse endpoint key: {e}"))?;
            if cert.tbs_certificate.subject_pki.raw != expected.contents() {
                bail!("MASQUE endpoint key pin mismatch");
            }
        }
    }

    let mut h3_config = quiche::h3::Config::new()
        .map_err(|e| anyhow::anyhow!("h3 config: {e}"))?;
    h3_config.enable_extended_connect(true);
    let mut h3 = quiche::h3::Connection::with_transport(&mut conn, &h3_config)
        .map_err(|e| anyhow::anyhow!("h3 connection: {e}"))?;

    let request = vec![
        quiche::h3::Header::new(b":method", b"CONNECT"),
        quiche::h3::Header::new(b":protocol", b"cf-connect-ip"),
        quiche::h3::Header::new(b":scheme", b"https"),
        quiche::h3::Header::new(b":authority", b"cloudflareaccess.com"),
        quiche::h3::Header::new(b":path", b"/"),
        quiche::h3::Header::new(b"capsule-protocol", b"?1"),
        quiche::h3::Header::new(b"user-agent", b""),
    ];
    let stream_id = h3
        .send_request(&mut conn, &request, false)
        .map_err(|e| anyhow::anyhow!("CONNECT request: {e}"))?;
    flush(&mut conn, config.udp_fd, config.peer, &mut out)?;

    let connect_deadline = Instant::now() + HANDSHAKE_TIMEOUT;
    let mut established = false;
    while !established {
        if stop.load(Ordering::Relaxed) {
            bail!("stopped");
        }
        if Instant::now() > connect_deadline {
            bail!("MASQUE CONNECT timeout");
        }
        let wait = conn
            .timeout()
            .unwrap_or(Duration::from_millis(250))
            .min(Duration::from_millis(250));
        if wait_readable(config.udp_fd, wait) {
            if let Ok(n) = recv_into(config.udp_fd, &mut in_buf) {
                let recv_info = quiche::RecvInfo {
                    to: config.local,
                    from: config.peer,
                };
                let _ = conn.recv(&mut in_buf[..n], recv_info);
            }
        }
        loop {
            match h3.poll(&mut conn) {
                Ok((id, quiche::h3::Event::Headers { list, .. })) if id == stream_id => {
                    for header in &list {
                        if header.name() == b":status" {
                            let status = std::str::from_utf8(header.value()).unwrap_or("?");
                            if status.starts_with('2') {
                                established = true;
                            } else {
                                bail!("MASQUE CONNECT rejected: {status}");
                            }
                        }
                    }
                }
                Ok(_) => {}
                Err(quiche::h3::Error::Done) => break,
                Err(e) => bail!("h3 poll: {e}"),
            }
        }
        conn.on_timeout();
        flush(&mut conn, config.udp_fd, config.peer, &mut out)?;
        if conn.is_closed() {
            bail!("QUIC closed before CONNECT response");
        }
    }

    let flow_id = stream_id / 4;
    let mut flow_prefix = Vec::with_capacity(16);
    {
        let mut tmp = [0u8; 8];
        let mut writer = OctetsMut::with_slice(&mut tmp);
        writer.put_varint(flow_id).unwrap();
        let used = writer.off();
        flow_prefix.extend_from_slice(&tmp[..used]);
    }
    flow_prefix.push(0);

    let mut poll_fds = [
        libc::pollfd {
            fd: config.tun_fd,
            events: libc::POLLIN,
            revents: 0,
        },
        libc::pollfd {
            fd: config.udp_fd,
            events: libc::POLLIN,
            revents: 0,
        },
    ];

    while !stop.load(Ordering::Relaxed) {
        let wait = conn
            .timeout()
            .unwrap_or(Duration::from_millis(250))
            .min(Duration::from_millis(250));
        let wait_ms = wait.as_millis().min(250) as i32;
        let ready = unsafe { libc::poll(poll_fds.as_mut_ptr(), 2, wait_ms) };

        if ready < 0 {
            let errno = std::io::Error::last_os_error();
            if errno.raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            bail!("poll failed");
        }

        if ready > 0 {
            if poll_fds[0].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
                break;
            }
            if poll_fds[1].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
                break;
            }

            if poll_fds[0].revents & libc::POLLIN != 0 {
                let n = unsafe {
                    libc::read(
                        config.tun_fd,
                        tun_buf.as_mut_ptr() as *mut libc::c_void,
                        tun_buf.len(),
                    )
                };
                if n > 0 {
                    let packet = &mut tun_buf[..n as usize];
                    if prepare_outgoing(packet).is_ok() {
                        let mut datagram = Vec::with_capacity(flow_prefix.len() + packet.len());
                        datagram.extend_from_slice(&flow_prefix);
                        datagram.extend_from_slice(packet);
                        match conn.dgram_send_vec(datagram) {
                            Ok(()) => {
                                stats.packets.fetch_add(1, Ordering::Relaxed);
                                stats.bytes.fetch_add(n as u64, Ordering::Relaxed);
                                stats.net_tx_packets.fetch_add(1, Ordering::Relaxed);
                            }
                            Err(quiche::Error::Done) => {}
                            Err(e) => {
                                bail!("datagram send: {e}");
                            }
                        }
                    }
                }
            }

            if poll_fds[1].revents & libc::POLLIN != 0 {
                if let Ok(n) = recv_into(config.udp_fd, &mut in_buf) {
                    stats.net_rx_packets.fetch_add(1, Ordering::Relaxed);
                    let recv_info = quiche::RecvInfo {
                        to: config.local,
                        from: config.peer,
                    };
                    let _ = conn.recv(&mut in_buf[..n], recv_info);
                }
            }
        }

        loop {
            match h3.poll(&mut conn) {
                Ok(_) => {}
                Err(quiche::h3::Error::Done) => break,
                Err(e) => bail!("h3 poll: {e}"),
            }
        }

        loop {
            match conn.dgram_recv_vec() {
                Ok(datagram) => {
                    if let Some(packet) = parse_datagram(&datagram, flow_id) {
                        if validate_incoming(packet).is_ok() {
                            stats.net_rx_packets.fetch_add(1, Ordering::Relaxed);
                            write_tun(config.tun_fd, packet);
                        }
                    }
                }
                Err(quiche::Error::Done) => break,
                Err(e) => bail!("datagram recv: {e}"),
            }
        }

        flush(&mut conn, config.udp_fd, config.peer, &mut out)?;
        conn.on_timeout();

        if conn.is_closed() {
            break;
        }
    }

    Ok(())
}

fn flush(
    conn: &mut quiche::Connection,
    udp_fd: i32,
    peer: SocketAddr,
    out: &mut [u8],
) -> Result<()> {
    loop {
        match conn.send(out) {
            Ok((len, _)) => {
                send_to(udp_fd, &out[..len], peer)?;
            }
            Err(quiche::Error::Done) => break,
            Err(e) => bail!("quic send: {e}"),
        }
    }
    Ok(())
}

fn wait_readable(fd: i32, timeout: Duration) -> bool {
    let mut poll_fd = libc::pollfd {
        fd,
        events: libc::POLLIN,
        revents: 0,
    };
    let timeout_ms = timeout.as_millis().min(250) as i32;
    unsafe { libc::poll(&mut poll_fd, 1, timeout_ms) > 0 }
}

fn recv_into(fd: i32, buffer: &mut [u8]) -> std::io::Result<usize> {
    let n = unsafe {
        libc::recv(
            fd,
            buffer.as_mut_ptr() as *mut libc::c_void,
            buffer.len(),
            0,
        )
    };
    if n < 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

fn send_to(fd: i32, data: &[u8], peer: SocketAddr) -> Result<()> {
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
                    data.as_ptr() as *const libc::c_void,
                    data.len(),
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
                    data.as_ptr() as *const libc::c_void,
                    data.len(),
                    0,
                    &sockaddr as *const libc::sockaddr_in6 as *const libc::sockaddr,
                    std::mem::size_of::<libc::sockaddr_in6>() as libc::socklen_t,
                )
            }
        }
    };
    if sent < 0 {
        bail!("udp send: {}", std::io::Error::last_os_error());
    }
    Ok(())
}

fn write_tun(fd: i32, packet: &[u8]) {
    unsafe {
        libc::write(
            fd,
            packet.as_ptr() as *const libc::c_void,
            packet.len(),
        );
    }
}

fn parse_datagram<'a>(datagram: &'a [u8], expected_flow_id: u64) -> Option<&'a [u8]> {
    let mut cursor = Octets::with_slice(datagram);
    let flow_id = cursor.get_varint().ok()?;
    if flow_id != expected_flow_id {
        return None;
    }
    let context_id = cursor.get_varint().ok()?;
    if context_id != 0 {
        return None;
    }
    let offset = cursor.off();
    if offset >= datagram.len() {
        return None;
    }
    Some(&datagram[offset..])
}

fn prepare_outgoing(packet: &mut [u8]) -> Result<u8> {
    if packet.is_empty() {
        bail!("empty packet");
    }
    match packet[0] >> 4 {
        4 => {
            if packet.len() < 20 {
                bail!("short IPv4");
            }
            if packet[8] <= 1 {
                bail!("TTL expired");
            }
            packet[8] -= 1;
            let checksum = ipv4_checksum(&packet[..20]);
            packet[10..12].copy_from_slice(&checksum.to_be_bytes());
            Ok(4)
        }
        6 => {
            if packet.len() < 40 {
                bail!("short IPv6");
            }
            if packet[7] <= 1 {
                bail!("hop limit expired");
            }
            packet[7] -= 1;
            Ok(6)
        }
        version => bail!("unknown IP version {version}"),
    }
}

fn validate_incoming(packet: &[u8]) -> Result<u8> {
    if packet.is_empty() {
        bail!("empty packet");
    }
    match packet[0] >> 4 {
        4 if packet.len() >= 20 => Ok(4),
        6 if packet.len() >= 40 => Ok(6),
        version => bail!("invalid incoming packet version {version}"),
    }
}

fn ipv4_checksum(header: &[u8]) -> u16 {
    let ihl = ((header[0] & 0x0f) as usize) * 4;
    let len = ihl.min(header.len());
    let mut sum = 0u32;
    let mut i = 0usize;
    while i < len {
        if i == 10 {
            i += 2;
            continue;
        }
        let word = if i + 1 < len {
            u16::from_be_bytes([header[i], header[i + 1]])
        } else {
            u16::from_be_bytes([header[i], 0])
        };
        sum += u32::from(word);
        i += 2;
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}
