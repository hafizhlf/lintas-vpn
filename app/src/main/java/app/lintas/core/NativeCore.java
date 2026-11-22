package app.lintas.core;

public final class NativeCore {
    private static final boolean LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("lintas_core");
            loaded = true;
        } catch (UnsatisfiedLinkError ignored) {
            loaded = false;
        }
        LOADED = loaded;
    }

    private NativeCore() {
    }

    public static boolean isAvailable() {
        return LOADED;
    }

    public static native long start(int tunFd);

    public static native long startWireGuard(
            int tunFd,
            int udpFd,
            byte[] privateKey,
            byte[] peerPublicKey,
            byte[] reserved,
            String peerAddress,
            int peerPort
    );

    public static native long startMasque(
            int tunFd,
            int udpFd,
            String certPath,
            String keyPath,
            String peerAddress,
            int peerPort,
            String localAddress,
            int localPort,
            String sni,
            String endpointPublicKeyPem
    );

    public static native void stop(long handle);

    public static native boolean isRunning(long handle);

    public static native long[] stats(long handle);
}
