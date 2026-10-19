package club.rainbowkitty.polytrains.ext;

/**
 * What a default method on one of these interfaces throws in place of the body its mixin supplies.
 *
 * <p>Each interface here is injected onto a vanilla class, and a mixin merges the real methods into
 * that class. The throwing defaults exist only so that a subclass this mod defines compiles: it
 * inherits the merged method, which always wins over an interface default, so reaching one of them
 * at all means the mixin did not apply.
 */
public final class Unmixed {

    // Static-only; blocks instantiation.
    private Unmixed() {}

    /** The error for a method its mixin should have supplied; the stack trace names which. */
    public static UnsupportedOperationException error() {
        return new UnsupportedOperationException("the mixin supplying this method did not apply");
    }
}
