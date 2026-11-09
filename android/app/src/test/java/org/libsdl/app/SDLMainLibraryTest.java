package org.libsdl.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import org.junit.After;
import org.junit.Test;
import org.tmnt.port.BuildConfig;
import org.tmnt.port.GameActivity;
import org.tmnt.port.NativeLaunch;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Runs the real pinned SDLActivity selection code and the real GameActivity override on the JVM.
 * CI executes this for both the clean and embedded build variants.
 */
public class SDLMainLibraryTest {
    private static final String LIB_DIR = "/data/app/org.tmnt.port/lib/arm64";

    @After public void clearSingleton() { SDLActivity.mSingleton = null; SDL.setContext(null); }

    private static Context context() {
        return new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo();
                info.nativeLibraryDir = LIB_DIR;
                return info;
            }
        };
    }

    @Test public void libraryOrderLoadsRuntimeBeforeEmbeddedCode() {
        assertArrayEquals(new String[]{"SDL2", "tmnt_runtime"}, NativeLaunch.libraries(false));
        assertArrayEquals(new String[]{"SDL2", "tmnt_runtime", "tmnt_code"}, NativeLaunch.libraries(true));
    }

    @Test public void pinnedSdlDefaultUsesLastLoadedLibrary() {
        // SDLActivity.getContext() is static and delegates to SDL.getContext().
        SDL.setContext(context());
        for (boolean embedded : new boolean[]{false, true}) {
            SDLActivity probe = new SDLActivity() {
                @Override protected String[] getLibraries() { return NativeLaunch.libraries(embedded); }
            };
            SDLActivity.mSingleton = probe;
            String expected = LIB_DIR + (embedded ? "/libtmnt_code.so" : "/libtmnt_runtime.so");
            assertEquals(expected, probe.getMainSharedObject());
        }
    }

    @Test public void gameActivityAlwaysSelectsRuntimeEntryPoint() {
        var game = new GameActivity() {
            @Override public Context getApplicationContext() { return context(); }
            public String entryLibrary() { return getMainSharedObject(); }
            public String entryFunction() { return getMainFunction(); }
        };
        SDLActivity.mSingleton = game;
        assertEquals(LIB_DIR + "/libtmnt_runtime.so", game.entryLibrary());
        assertEquals("SDL_main", game.entryFunction());
        // The current variant must still load the runtime before any embedded game code.
        String[] libraries = NativeLaunch.libraries(BuildConfig.EMBEDDED_GAME);
        assertEquals("tmnt_runtime", libraries[1]);
        if (BuildConfig.EMBEDDED_GAME) assertEquals("tmnt_code", libraries[libraries.length - 1]);
    }
}
