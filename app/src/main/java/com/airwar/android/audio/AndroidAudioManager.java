package com.airwar.android.audio;

import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;

import com.airwar.android.R;

public class AndroidAudioManager {
    public interface Backend {
        void playBgmGame();

        void playBgmBoss();

        void stopAllBgm();

        boolean isBgmPlaying();

        default void playBulletSfx() {
        }

        default void playHitSfx() {
        }

        default void playBombSfx() {
        }

        default void playSupplySfx() {
        }

        default void playGameOverSfx() {
        }

        default void release() {
        }
    }

    private final Backend backend;
    private boolean enabled = true;
    private BgmTrack activeTrack = BgmTrack.NONE;

    private enum BgmTrack {
        NONE,
        GAME,
        BOSS
    }

    public AndroidAudioManager(Activity activity) {
        this(createBackend(activity));
    }

    public AndroidAudioManager(Backend backend) {
        this.backend = backend == null ? new NoOpBackend() : backend;
    }

    public void playBgmGame() {
        if (!enabled) {
            return;
        }
        if (activeTrack == BgmTrack.GAME && backend.isBgmPlaying()) {
            return;
        }
        backend.playBgmGame();
        activeTrack = BgmTrack.GAME;
    }

    public void playBgmBoss() {
        if (!enabled) {
            return;
        }
        if (activeTrack == BgmTrack.BOSS && backend.isBgmPlaying()) {
            return;
        }
        backend.playBgmBoss();
        activeTrack = BgmTrack.BOSS;
    }

    public void stopAllBgm() {
        backend.stopAllBgm();
        activeTrack = BgmTrack.NONE;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            backend.stopAllBgm();
            activeTrack = BgmTrack.NONE;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isBgmPlaying() {
        return enabled && backend.isBgmPlaying();
    }

    public void playBulletSfx() {
        if (enabled) {
            backend.playBulletSfx();
        }
    }

    public void playHitSfx() {
        if (enabled) {
            backend.playHitSfx();
        }
    }

    public void playBombSfx() {
        if (enabled) {
            backend.playBombSfx();
        }
    }

    public void playSupplySfx() {
        if (enabled) {
            backend.playSupplySfx();
        }
    }

    public void playGameOverSfx() {
        if (enabled) {
            backend.playGameOverSfx();
        }
    }

    public void release() {
        backend.release();
        activeTrack = BgmTrack.NONE;
    }

    private static Backend createBackend(Activity activity) {
        if (activity == null) {
            return new NoOpBackend();
        }
        return new RealBackend(activity.getApplicationContext());
    }

    private static final class NoOpBackend implements Backend {
        @Override
        public void playBgmGame() {
        }

        @Override
        public void playBgmBoss() {
        }

        @Override
        public void stopAllBgm() {
        }

        @Override
        public boolean isBgmPlaying() {
            return false;
        }
    }

    private static final class RealBackend implements Backend {
        private final MediaPlayer bgmGame;
        private final MediaPlayer bgmBoss;
        private final SoundPool soundPool;
        private final int bulletSfxId;
        private final int hitSfxId;
        private final int bombSfxId;
        private final int supplySfxId;
        private final int gameOverSfxId;

        private RealBackend(Context context) {
            bgmGame = MediaPlayer.create(context, R.raw.bgm);
            bgmBoss = MediaPlayer.create(context, R.raw.bgm_boss);

            if (bgmGame != null) {
                bgmGame.setLooping(true);
            }
            if (bgmBoss != null) {
                bgmBoss.setLooping(true);
            }

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setAudioAttributes(attrs)
                    .setMaxStreams(6)
                    .build();
            bulletSfxId = soundPool.load(context, R.raw.bullet, 1);
            hitSfxId = soundPool.load(context, R.raw.bullet_hit, 1);
            bombSfxId = soundPool.load(context, R.raw.bomb_explosion, 1);
            supplySfxId = soundPool.load(context, R.raw.get_supply, 1);
            gameOverSfxId = soundPool.load(context, R.raw.game_over, 1);
        }

        @Override
        public void playBgmGame() {
            stopAllBgm();
            if (bgmGame != null) {
                bgmGame.start();
            }
        }

        @Override
        public void playBgmBoss() {
            stopAllBgm();
            if (bgmBoss != null) {
                bgmBoss.start();
            }
        }

        @Override
        public void stopAllBgm() {
            if (bgmGame != null && bgmGame.isPlaying()) {
                bgmGame.pause();
                bgmGame.seekTo(0);
            }
            if (bgmBoss != null && bgmBoss.isPlaying()) {
                bgmBoss.pause();
                bgmBoss.seekTo(0);
            }
        }

        @Override
        public boolean isBgmPlaying() {
            return (bgmGame != null && bgmGame.isPlaying()) || (bgmBoss != null && bgmBoss.isPlaying());
        }

        @Override
        public void playBulletSfx() {
            playSound(bulletSfxId);
        }

        @Override
        public void playHitSfx() {
            playSound(hitSfxId);
        }

        @Override
        public void playBombSfx() {
            playSound(bombSfxId);
        }

        @Override
        public void playSupplySfx() {
            playSound(supplySfxId);
        }

        @Override
        public void playGameOverSfx() {
            playSound(gameOverSfxId);
        }

        @Override
        public void release() {
            stopAllBgm();
            if (bgmGame != null) {
                bgmGame.release();
            }
            if (bgmBoss != null) {
                bgmBoss.release();
            }
            soundPool.release();
        }

        private void playSound(int id) {
            soundPool.play(id, 1f, 1f, 1, 0, 1f);
        }
    }
}
