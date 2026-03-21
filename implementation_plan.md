# Reverse Flappy Bird — Conversion Plan

Convert the existing Flappy Bird into a **Reverse Flappy Bird** where the player controls the pipe gap to trap an AI-controlled bird.

> [!IMPORTANT]
> Only [GameView.kt](file:///C:/Users/youge/AndroidStudioProjects/MyApplication2/app/src/main/java/com/example/myapplication/GameView.kt) is modified. [GameThread.kt](file:///c:/Users/youge/AndroidStudioProjects/MyApplication2/app/src/main/java/com/example/myapplication/GameThread.kt) and [MainActivity.kt](file:///C:/Users/youge/AndroidStudioProjects/MyApplication2/app/src/main/java/com/example/myapplication/MainActivity.kt) remain untouched.

## Proposed Changes

### [MODIFY] [GameView.kt](file:///c:/Users/youge/AndroidStudioProjects/MyApplication2/app/src/main/java/com/example/myapplication/GameView.kt)

All changes are inside this single file. Grouped by feature:

---

#### 1. Remove Player Bird Control → Add Auto-Bird AI

- **REMOVE** tap-to-flap in [onTouchEvent](file:///c:/Users/youge/Dropbox/PC/Downloads/Flappy-bird-using-c-SFML/FlappyBirdAndroid/app/src/main/java/com/yougesh/flappybird/GameView.kt#104-123) for `STARTED` state
- **ADD** auto-bird logic in [update()](file:///C:/Users/youge/AndroidStudioProjects/MyApplication2/app/src/main/java/com/example/myapplication/GameView.kt#203-325):
  - Gravity + velocity still applies (existing code stays)
  - Bird randomly flaps upward every ~40-70 frames to simulate life
  - **Simple AI**: If nearest pipe gap is above bird → slight upward impulse; if below → let gravity pull down
  - Bird AI strength increases per level (smarter bird = harder to trap)

#### 2. Player Controls Pipe Gap (Touch → Pipe Y)

- **ADD** fields: `touchTargetY`, `lastMoveTimeMs`, `isTouching`
- **MODIFY** [onTouchEvent](file:///c:/Users/youge/Dropbox/PC/Downloads/Flappy-bird-using-c-SFML/FlappyBirdAndroid/app/src/main/java/com/yougesh/flappybird/GameView.kt#104-123):
  - `ACTION_DOWN` / `ACTION_MOVE` → set `touchTargetY` to touch Y (scaled to virtual coords)
  - Only the **nearest pipe** (closest pipe with `x > FLAPPY_X - 100`) is controllable
- **ADD** smooth movement: pipe `gapY` lerps toward `touchTargetY` at 12px/frame
- **ADD** constraints:
  - `MIN_GAP = 250f` — gap never closes below this
  - **Lock Zone**: if bird is within 150px horizontally of the pipe → freeze pipe movement
  - **Cooldown**: 300ms between pipe position updates

#### 3. Modify Scoring — "Escapes Before Trap"

- **MODIFY** scoring: score increments when bird **passes** a pipe (bird escaped = +1)
- When bird **dies** (collision or out of bounds) → GAME OVER
- Score represents "how many pipes the bird escaped before you trapped it"
- Lower score = better performance by the player

#### 4. Level System — Target-Based Progression

- **MODIFY** level logic:
  - Each level has a `targetPipes` (e.g., level 1 = 5 pipes to survive)
  - If bird is trapped **before** reaching target → **LEVEL COMPLETE** → advance
  - If bird survives past target → **LEVEL FAILED** → retry same level
- **ADD** `pipesThisLevel` counter, `targetPipes` computed from level
- Difficulty per level: bird speed ↑, pipe gap ↓, pipe speed ↑, bird AI smarts ↑
- Player does NOT restart from level 1 on failure

#### 5. Simple Bird AI

- Inside [update()](file:///C:/Users/youge/AndroidStudioProjects/MyApplication2/app/src/main/java/com/example/myapplication/GameView.kt#203-325), after gravity:
  - Find nearest pipe ahead of bird
  - If pipe gap center is above bird → apply small upward impulse (~-4f to -8f)
  - If gap center is below bird → reduce upward velocity
  - Random noise added to prevent perfect play
  - AI reaction strength scales with level (level 1: weak, level 6: strong)

#### 6. Trap System — Nets & Air Mines

- **ADD** `Trap` class: [(x, y, type: TrapType, active: Boolean)](file:///c:/Users/youge/Dropbox/PC/Downloads/Flappy-bird-using-c-SFML/FlappyBirdAndroid/app/src/main/java/com/yougesh/flappybird/GameThread.kt#10-52)
- **ADD** `enum TrapType { NET, MINE }`
- Spawn traps randomly inside pipe gaps:
  - Levels 1-3: Nets only (slow bird velocity by 50%)
  - Levels 4+: Mix of Nets and Air Mines (mine = instant game over)
- Draw traps as simple canvas shapes (lines for net, red circle for mine)
- Collision check against bird in [update()](file:///C:/Users/youge/AndroidStudioProjects/MyApplication2/app/src/main/java/com/example/myapplication/GameView.kt#203-325)

#### 7. Worm Bonus (Extend Existing)

- Worms already exist — **MODIFY** behavior:
  - If bird collects worm → temporary speed boost (2x for 120 frames)
  - If player traps bird while worm boost is active → bonus +5 score
- **ADD** `wormBoostFrames` counter

#### 8. UI Text Updates

- **MODIFY** draw text:
  - Show "ESCAPES: X" instead of raw score
  - Show "TARGET: X" for current level's target
  - Show "LEVEL COMPLETE!" or "LEVEL FAILED" on game over
  - "DRAG TO MOVE PIPES" on waiting screen

## Verification Plan

### Manual Verification
- Build in Android Studio with Run button
- Confirm bird moves automatically without player input
- Confirm touch/drag moves nearest pipe gap smoothly
- Confirm lock zone prevents pipe movement when bird is close
- Confirm level progression works (complete + fail scenarios)
- Confirm traps spawn and affect bird correctly
- Confirm worm boost and bonus score work
- Confirm 60 FPS performance maintained
