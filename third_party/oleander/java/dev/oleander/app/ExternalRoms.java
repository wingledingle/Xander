package dev.oleander.app;

import android.content.Context;

import java.io.File;
import java.util.List;

/**
 * Optional extra source of games for the library. Inside Xander's all-in-one build it is set to Xander's storage
 * index (ROMs found anywhere, including inside zips); the standalone app leaves it unset and uses its folder scan.
 */
public final class ExternalRoms {
    public interface Provider { List<File> roms(Context context); }

    public static volatile Provider provider;

    private ExternalRoms() {}
}
