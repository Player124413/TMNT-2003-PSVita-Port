package org.tmnt.port;

/** Pure launch contract shared by GameActivity and the host-side regression test. */
public final class NativeLaunch {
    public static final String RUNTIME_LIBRARY = "tmnt_runtime";
    public static final String MAIN_FUNCTION = "SDL_main";
    private NativeLaunch() {}

    /** Load order matters: SDL first, runtime second, embedded game code last. */
    public static String[] libraries(boolean embeddedGame) {
        return embeddedGame
            ? new String[]{"SDL2", RUNTIME_LIBRARY, "tmnt_code"}
            : new String[]{"SDL2", RUNTIME_LIBRARY};
    }

    /**
     * SDLActivity's default implementation uses the last loaded library. That is wrong for
     * embedded APKs: libtmnt_code.so exports game code, while SDL_main lives in the runtime.
     */
    public static String mainLibrary(String nativeLibraryDir) {
        return nativeLibraryDir + "/lib" + RUNTIME_LIBRARY + ".so";
    }
}
