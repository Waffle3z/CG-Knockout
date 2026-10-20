# Knockout

Source of the CodinGame multiplayer game **Knockout**: sumo with circles. Each player controls 6
round units on a frictionless, shrinking ring and tries to push every enemy unit out. Each turn a
unit thrusts toward a point, and it can spend stamina to **DASH** (5x thrust) or **PLANT** (grip
that brakes and resists a single pusher).

Built on the CodinGame SDK (`com.codingame.gameengine` 4.7.8). The rules, protocol and the complete
simulation are in the statement, `config/statement_en.html`.

## What is here

- `src/main/java/com/codingame/game/knockout/World.java` -- the whole simulation, no SDK dependency.
  It follows the statement's *Expert Rules* pseudocode line for line, including the order of
  floating-point operations, so you can reproduce the referee bit for bit.
- `src/main/java/com/codingame/game/Referee.java` -- protocol, timeouts, scoring.
- `src/main/resources/view/` -- the viewer: one canvas with procedural art (`ring-module/`), a
  settings menu (debug view with velocity/thrust arrows, light/dark theme) and timeline markers
  for every knockout.
- `src/test/java/com/codingame/game/knockout/WorldTest.java` -- replays recorded games
  (`src/test/resources/traces.txt`) and demands identical integer state after every turn. It makes
  a good test for your own simulation: per game, `U id owner x y vx vy stamina` gives the start,
  then every turn has `TURN radius`, one `A id x y power mode` command per unit (`-` = no ability)
  and the resulting `S id x y vx vy stamina alive` per unit.
- `config/` -- statement, stub, league welcome texts.

The league bosses are **not** published: beating them is part of the challenge. The statement's
*Leagues* section describes what each one does.

## Build and run locally

Requires Java 17+ and Maven.

```
mvn test
```

`src/test/java/Main.java` serves the viewer at `http://localhost:8890/test.html` and a small game
launcher at `http://localhost:8891/`. Put your bots in `target/bots/` (`<name>.exe`, `<name>.py`
or `<name>.jar`), then run `Main` with the test classpath:

```
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -DincludeScope=test
java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" Main <seed> 8890 <botA> <botB>
```

(on Windows use `;` as the classpath separator). Seed `0` picks a random one.
