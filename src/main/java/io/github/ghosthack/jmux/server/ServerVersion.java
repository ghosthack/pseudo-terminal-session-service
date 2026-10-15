package io.github.ghosthack.jmux.server;

/**
 * The artifact version a server reports on {@code ping} and the CLI prints on
 * {@code version}: the jar manifest's {@code Implementation-Version} when the class was
 * loaded from a jar that carries one, else {@code "dev"} (an IDE or {@code target/classes}
 * run). Never null, so a mismatch message always has something to name.
 */
public final class ServerVersion {

    public static final String DEV = "dev";

    public static String current() {
        Package pkg = ServerVersion.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version == null || version.isBlank() ? DEV : version;
    }

    private ServerVersion() {
    }
}
