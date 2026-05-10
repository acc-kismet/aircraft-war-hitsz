package com.airwar.android.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.airwar.android.audio.AndroidAudioManager;
import com.airwar.core.config.GameConstants;
import com.airwar.core.difficulty.DifficultyLevel;
import com.airwar.core.engine.GameEngine;
import com.airwar.core.engine.GameStateSnapshot;

import java.util.Locale;

public class GameSurfaceView extends SurfaceView implements SurfaceHolder.Callback, GameRenderThread.FrameListener {
    private GameEngine engine;
    private final SpriteRepository spriteRepository;
    private final Object engineLock = new Object();
    private DifficultyLevel difficultyLevel = DifficultyLevel.NORMAL;

    private GameRenderThread renderThread;
    private AndroidAudioManager audioManager;
    private SnapshotListener snapshotListener;
    private boolean bossAudioActive;
    private int lastHeroShotEvents;
    private int lastHitEvents;
    private int lastExplosionEvents;
    private int lastSupplyEvents;
    private int lastGameOverEvents;

    public GameSurfaceView(Context context) {
        this(context, null);
    }

    public GameSurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GameSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        engine = GameEngine.create(difficultyLevel);
        spriteRepository = new SpriteRepository(context);
        getHolder().addCallback(this);
        setFocusable(true);
    }

    public void setDifficulty(String difficulty) {
        DifficultyLevel parsed = parseDifficulty(difficulty);
        synchronized (engineLock) {
            spriteRepository.setDifficulty(difficulty, getContext());
            if (renderThread == null) {
                difficultyLevel = parsed;
                engine = GameEngine.create(difficultyLevel);
            }
        }
    }

    public void setAudioManager(AndroidAudioManager audioManager) {
        this.audioManager = audioManager;
    }

    public void setSnapshotListener(SnapshotListener snapshotListener) {
        this.snapshotListener = snapshotListener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            int logicalX = toLogicalX(event.getX());
            int logicalY = toLogicalY(event.getY());
            synchronized (engineLock) {
                engine.setHeroTarget(logicalX, logicalY);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        stopRenderThreadIfNeeded();
        renderThread = new GameRenderThread(holder, engine, engineLock, spriteRepository, this);
        renderThread.requestStart();
        renderThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopRenderThreadIfNeeded();
    }

    private void stopRenderThreadIfNeeded() {
        if (renderThread == null) {
            return;
        }

        GameRenderThread thread = renderThread;
        thread.requestStop();
        boolean interrupted = false;
        int retries = 0;
        while (thread.isAlive() && retries < 20) {
            try {
                thread.join(50L);
            } catch (InterruptedException ignored) {
                interrupted = true;
                retries++;
                continue;
            }
            retries++;
        }

        if (thread.isAlive()) {
            renderThread = null;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        renderThread = null;
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static DifficultyLevel parseDifficulty(String difficulty) {
        if (difficulty == null) {
            return DifficultyLevel.NORMAL;
        }
        return switch (difficulty.toLowerCase(Locale.ROOT)) {
            case "easy" -> DifficultyLevel.EASY;
            case "hard" -> DifficultyLevel.HARD;
            default -> DifficultyLevel.NORMAL;
        };
    }

    @Override
    public void onFrame(GameStateSnapshot snapshot) {
        if (audioManager != null) {
            if (snapshot.bossActive() && !bossAudioActive) {
                audioManager.playBgmBoss();
                bossAudioActive = true;
            } else if (!snapshot.bossActive() && bossAudioActive) {
                audioManager.playBgmGame();
                bossAudioActive = false;
            }

            if (snapshot.heroShotEvents() > lastHeroShotEvents) {
                audioManager.playBulletSfx();
            }
            if (snapshot.hitEvents() > lastHitEvents) {
                audioManager.playHitSfx();
            }
            if (snapshot.explosionEvents() > lastExplosionEvents) {
                audioManager.playBombSfx();
            }
            if (snapshot.supplyEvents() > lastSupplyEvents) {
                audioManager.playSupplySfx();
            }
            if (snapshot.gameOverEvents() > lastGameOverEvents) {
                audioManager.playGameOverSfx();
            }
        }

        lastHeroShotEvents = snapshot.heroShotEvents();
        lastHitEvents = snapshot.hitEvents();
        lastExplosionEvents = snapshot.explosionEvents();
        lastSupplyEvents = snapshot.supplyEvents();
        lastGameOverEvents = snapshot.gameOverEvents();

        if (snapshotListener != null) {
            post(() -> snapshotListener.onSnapshot(snapshot));
        }
    }

    public void refreshAudioTrack() {
        if (audioManager == null) {
            return;
        }
        if (bossAudioActive) {
            audioManager.playBgmBoss();
            return;
        }
        audioManager.playBgmGame();
    }

    public interface SnapshotListener {
        void onSnapshot(GameStateSnapshot snapshot);
    }

    private int toLogicalX(float rawX) {
        int width = getWidth();
        if (width <= 0) {
            return Math.round(rawX);
        }
        float logical = rawX * GameConstants.LOGICAL_WIDTH / width;
        return clamp(Math.round(logical), 0, GameConstants.LOGICAL_WIDTH);
    }

    private int toLogicalY(float rawY) {
        int height = getHeight();
        if (height <= 0) {
            return Math.round(rawY);
        }
        float logical = rawY * GameConstants.LOGICAL_HEIGHT / height;
        return clamp(Math.round(logical), 0, GameConstants.LOGICAL_HEIGHT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
