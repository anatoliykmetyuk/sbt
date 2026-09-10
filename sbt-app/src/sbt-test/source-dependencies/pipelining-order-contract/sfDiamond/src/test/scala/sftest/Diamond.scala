/*
 * sbt
 * Copyright 2026, Scala Center, and the sbt contributors.
 * Licensed under Apache License 2.0 (see LICENSE).
 */

package sftest
class Diamond { def value: Int = new Left().value + new Right().value }
