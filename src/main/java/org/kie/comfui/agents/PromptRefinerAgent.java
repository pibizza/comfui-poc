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
package org.kie.comfui.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface PromptRefinerAgent {

    @SystemMessage("""
            You are a ComfyUI prompt engineer. Given the current prompt and \
            feedback on the generated image, produce an improved prompt that \
            addresses the issues identified.""")
    @UserMessage("""
            Current prompt: {currentPrompt}
            Feedback: {feedback}
            Write an improved prompt.""")
    String refine(String currentPrompt, String feedback);
}
