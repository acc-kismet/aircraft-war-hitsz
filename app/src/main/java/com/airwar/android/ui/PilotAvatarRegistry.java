package com.airwar.android.ui;

import com.airwar.android.R;

public final class PilotAvatarRegistry {
    public static final String DEFAULT_AVATAR_ID = "pilot_01";
    public static final String[] IDS = new String[] {
            "pilot_01",
            "pilot_02",
            "pilot_03",
            "pilot_04",
            "pilot_05",
            "pilot_06"
    };

    private PilotAvatarRegistry() {
    }

    public static int drawableFor(String avatarId) {
        String id = avatarId == null ? DEFAULT_AVATAR_ID : avatarId;
        return switch (id) {
            case "pilot_01" -> R.drawable.avatar_01;
            case "pilot_02" -> R.drawable.avatar_02;
            case "pilot_03" -> R.drawable.avatar_03;
            case "pilot_04" -> R.drawable.avatar_04;
            case "pilot_05" -> R.drawable.avatar_05;
            case "pilot_06" -> R.drawable.avatar_06;
            default -> R.drawable.avatar_01;
        };
    }
}
