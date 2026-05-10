package com.airwar.android.ui;

import com.airwar.android.R;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PilotAvatarRegistryTest {

    @Test
    public void drawableForUsesRenamedAvatarPngResources() {
        assertEquals(R.drawable.avatar_01, PilotAvatarRegistry.drawableFor("pilot_01"));
        assertEquals(R.drawable.avatar_02, PilotAvatarRegistry.drawableFor("pilot_02"));
        assertEquals(R.drawable.avatar_03, PilotAvatarRegistry.drawableFor("pilot_03"));
        assertEquals(R.drawable.avatar_04, PilotAvatarRegistry.drawableFor("pilot_04"));
        assertEquals(R.drawable.avatar_05, PilotAvatarRegistry.drawableFor("pilot_05"));
        assertEquals(R.drawable.avatar_06, PilotAvatarRegistry.drawableFor("pilot_06"));
    }

    @Test
    public void drawableForFallsBackToFirstRenamedAvatar() {
        assertEquals(R.drawable.avatar_01, PilotAvatarRegistry.drawableFor("unknown"));
        assertEquals(R.drawable.avatar_01, PilotAvatarRegistry.drawableFor(null));
    }
}
