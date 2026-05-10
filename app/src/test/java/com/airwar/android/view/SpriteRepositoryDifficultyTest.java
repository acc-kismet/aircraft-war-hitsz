package com.airwar.android.view;

import com.airwar.android.R;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpriteRepositoryDifficultyTest {

    @Test
    public void backgroundResourceMatchesDifficulty() {
        assertEquals(R.drawable.bg2, SpriteRepository.backgroundResForDifficulty("easy"));
        assertEquals(R.drawable.bg3, SpriteRepository.backgroundResForDifficulty("normal"));
        assertEquals(R.drawable.bg5, SpriteRepository.backgroundResForDifficulty("hard"));
        assertEquals(R.drawable.bg3, SpriteRepository.backgroundResForDifficulty("unexpected"));
        assertEquals(R.drawable.bg3, SpriteRepository.backgroundResForDifficulty(null));
    }
}
