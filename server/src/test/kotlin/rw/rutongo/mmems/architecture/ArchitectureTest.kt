package rw.rutongo.mmems.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator
import com.lemonappdev.konsist.api.architecture.Layer
import kotlin.test.Test

class ArchitectureTest {
    @Test
    fun `shared and platform have no project dependencies`() {
        with(KoArchitectureCreator) {
            Konsist
                .scopeFromProduction()
                .assertArchitecture {
                    val shared = Layer("Shared", "rw.rutongo.mmems.shared..")
                    val platform = Layer("Platform", "rw.rutongo.mmems.platform..")

                    shared.dependsOnNothing()
                    platform.dependsOnNothing()
                }
        }
    }

    @Test
    fun `identity depends on shared and not on modules`() {
        with(KoArchitectureCreator) {
            Konsist
                .scopeFromProduction()
                .assertArchitecture {
                    val shared = Layer("Shared", "rw.rutongo.mmems.shared..")
                    val identity = Layer("Identity", "rw.rutongo.mmems.identity..")
                    val modules = Layer("Modules", "rw.rutongo.mmems.modules..")

                    identity.dependsOn(shared, strict = true)
                    identity.doesNotDependOn(modules)
                }
        }
    }

    @Test
    fun `modules depend on identity, shared and platform only`() {
        with(KoArchitectureCreator) {
            Konsist
                .scopeFromProduction()
                .assertArchitecture {
                    val shared = Layer("Shared", "rw.rutongo.mmems.shared..")
                    val platform = Layer("Platform", "rw.rutongo.mmems.platform..")
                    val identity = Layer("Identity", "rw.rutongo.mmems.identity..")
                    val modules = Layer("Modules", "rw.rutongo.mmems.modules..")

                    modules.dependsOn(identity, shared, platform, strict = true)
                }
        }
    }

    @Test
    fun `status domain depends only on shared`() {
        with(KoArchitectureCreator) {
            Konsist
                .scopeFromProduction()
                .assertArchitecture {
                    val shared = Layer("Shared", "rw.rutongo.mmems.shared..")
                    val platform = Layer("Platform", "rw.rutongo.mmems.platform..")
                    val identity = Layer("Identity", "rw.rutongo.mmems.identity..")
                    val statusDomain = Layer("Status domain", "rw.rutongo.mmems.modules.status.domain..")

                    statusDomain.dependsOn(shared, strict = true)
                    statusDomain.doesNotDependOn(identity, platform)
                }
        }
    }

    @Test
    fun `breakdown domain depends only on shared`() {
        with(KoArchitectureCreator) {
            Konsist
                .scopeFromProduction()
                .assertArchitecture {
                    val shared = Layer("Shared", "rw.rutongo.mmems.shared..")
                    val platform = Layer("Platform", "rw.rutongo.mmems.platform..")
                    val identity = Layer("Identity", "rw.rutongo.mmems.identity..")
                    val breakdownDomain = Layer("Breakdown domain", "rw.rutongo.mmems.modules.breakdown.domain..")

                    breakdownDomain.dependsOn(shared, strict = true)
                    breakdownDomain.doesNotDependOn(identity, platform)
                }
        }
    }
}
