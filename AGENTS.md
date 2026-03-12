## Cursor Cloud specific instructions

This is the **sbt** (Scala Build Tool) 2.x series source repository. It is a pure JVM project—no databases, Docker, or external services are needed.

### Prerequisites

- **JDK 21** (max supported; see `DEVELOPING.md`). Set `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- The `./sbt` launcher script at repo root downloads the sbt-launch jar automatically; no global sbt install required.
- Git submodule `lm-coursier/metadata` must be initialised (`git submodule update --init`).

### Running sbt commands

The `./sbt` script defaults to the **native client (sbtn)** for sbt 2.x builds. In headless/CI environments, always use `--server` to force the JVM-based sbt and `--batch` to disable interactive mode:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./sbt --server --batch <commands>
```

### Key commands (see `DEVELOPING.md` for full details)

| Task | Command |
|------|---------|
| Compile all | `./sbt --server --batch compile` |
| Run all unit tests | `./sbt --server --batch test` |
| Run specific subproject tests | `./sbt --server --batch "<project>/test"` (e.g. `utilCache/test`) |
| Scalafmt check | `./sbt --server --batch scalafmtCheckAll` |
| Publish locally | `./sbt --server --batch publishLocal` |
| Run a scripted integration test | `./sbt --server --batch "scripted project/global-plugin"` |

### Non-obvious notes

- First `compile` or `test` takes ~2 minutes while sbt resolves and caches all dependencies.
- Subsequent runs inside the same JVM session (interactive sbt shell) are much faster (~10s).
- `.jvmopts` sets `-Xmx2G`; `.sbtopts` mirrors this. Ensure the VM has sufficient memory.
- `scalafmtOnCompile` is enabled outside CI, so compilation also formats code automatically.
- The project version is `2.0.0-RC6-bin-SNAPSHOT`; `publishLocal` publishes to `~/.ivy2/local/`.
- To test a locally-built sbt on a separate project, set `sbt.version=2.0.0-RC6-bin-SNAPSHOT` in that project's `project/build.properties` and run with the `./sbt` launcher.
- Subproject names in sbt commands use the sbt project ID (e.g. `utilCache`, `utilRelation`, `coreMacrosProj`, `mainProj`), not directory names. These are defined in `build.sbt`.
