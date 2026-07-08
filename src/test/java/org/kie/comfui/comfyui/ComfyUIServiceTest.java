/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.comfui.comfyui;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ComfyUIServiceTest {

    // WireMock stubs HTTP REST calls (POST /prompt, GET /history, GET /view).
    // The WebSocket completion signal comes from TestComfyUIWebSocketServer served by the Quarkus test container.
    static final WireMockServer wireMock = new WireMockServer(18188);

    @BeforeAll
    static void startWireMock() {
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @Inject
    ComfyUIService service;

    @Test
    void generateReturnsImageBytes() {
        final String promptId = "test-prompt-id";
        final byte[] fakeImage = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic bytes

        wireMock.stubFor(post(urlEqualTo("/prompt"))
                .willReturn(okJson("{\"prompt_id\":\"" + promptId + "\"}")));

        wireMock.stubFor(get(urlMatching("/history/" + promptId))
                .willReturn(okJson("{\"" + promptId + "\":{\"outputs\":{\"9\":{\"images\":"
                        + "[{\"filename\":\"test.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}")));

        wireMock.stubFor(get(urlMatching("/view.*"))
                .willReturn(aResponse().withBody(fakeImage)));

        final byte[] result = service.generate("a cat in a hat");

        assertThat(result).isNotEmpty();
    }
}
