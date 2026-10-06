package io.twba.search.toolkit.testkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opensearch.testcontainers.OpenSearchContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * The fixture's promises that hold before anything is started.
 *
 * <p>Nothing here starts a container, and that is deliberate: the two properties worth pinning —
 * that the image is pinned, and that the caller owns the lifecycle — are both properties of an
 * <em>unstarted</em> container, and asserting them against a running one would cost ninety seconds
 * to learn nothing more.
 */
class ToolkitOpenSearchClusterTest {

    @Nested
    @DisplayName("the container it hands back")
    class TheContainer {

        @Test
        @DisplayName("is pinned to one image tag, so a run's result does not depend on when it ran")
        void isPinnedToOneImageTag() {
            // A floating tag would make a conformance result a function of the calendar, which is the
            // opposite of what a conformance run is for.
            assertThat(ToolkitOpenSearchCluster.IMAGE.asCanonicalNameString())
                    .isEqualTo("opensearchproject/opensearch:3.7.0");
            assertThat(ToolkitOpenSearchCluster.container().getDockerImageName())
                    .isEqualTo(ToolkitOpenSearchCluster.IMAGE.asCanonicalNameString());
        }

        @Test
        @DisplayName("comes back unstarted, for the caller's own lifecycle to manage")
        void comesBackUnstarted() {
            assertThat(ToolkitOpenSearchCluster.container().isRunning()).isFalse();
        }

        @Test
        @DisplayName("is a new container each time, never a shared singleton")
        void isANewContainerEachTime() {
            // A shared singleton would be faster and would also let one suite's leftover indices
            // decide another suite's result — the exact failure a conformance run must not have.
            OpenSearchContainer<?> first = ToolkitOpenSearchCluster.container();
            OpenSearchContainer<?> second = ToolkitOpenSearchCluster.container();

            assertThat(first).isNotSameAs(second);
        }

        @Test
        @DisplayName("has the security plugin switched on")
        void hasSecurityEnabled() {
            // Two of the checks that matter most involve the cluster enforcing something, and a
            // cluster with the plugin disabled is a different cluster. The admin password env var is
            // only ever set by withSecurityEnabled()'s code path here, so its presence is the
            // observable trace of the plugin being on.
            assertThat(ToolkitOpenSearchCluster.container().getEnvMap())
                    .containsKey("OPENSEARCH_INITIAL_ADMIN_PASSWORD");
        }

        @Test
        @DisplayName("does not carry the development cluster's password")
        void doesNotCarryTheDevelopmentPassword() {
            // A credential that appears in two places is one that will eventually be copied to a
            // third. The compose cluster's password stays in dev config and nowhere near here.
            assertThat(ToolkitOpenSearchCluster.container().getEnvMap())
                    .doesNotContainValue("LabMw!Zero2Hero#2026");
        }
    }

    @Nested
    @DisplayName("the client it builds")
    class TheClient {

        @Test
        @DisplayName("refuses a container that is not running, and says what to do about it")
        void refusesAnUnstartedContainer() {
            // The alternative is a client pointed at getMappedPort() on a container with no ports,
            // which fails much later with an exception about the cluster rather than about the test.
            OpenSearchContainer<?> unstarted = ToolkitOpenSearchCluster.container();

            assertThatIllegalStateException()
                    .isThrownBy(() -> ToolkitOpenSearchCluster.clientFor(unstarted))
                    .withMessageContaining("not running")
                    .withMessageContaining("start it");
        }

        @Test
        @DisplayName("refuses a null container by name")
        void refusesANullContainer() {
            assertThatNullPointerException()
                    .isThrownBy(() -> ToolkitOpenSearchCluster.clientFor(null))
                    .withMessageContaining("container");
        }
    }
}
