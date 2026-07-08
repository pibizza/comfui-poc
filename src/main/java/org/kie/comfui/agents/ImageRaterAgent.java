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
import io.quarkiverse.langchain4j.ModelName;
import io.quarkiverse.langchain4j.RegisterAiService;
import org.kie.comfui.domain.RatingResult;

@RegisterAiService
@ModelName("vision")
public interface ImageRaterAgent {

    @SystemMessage("""
            You are an image quality evaluator. Given a prompt and an image, \
            score how well the image matches the prompt on a scale of 0-100. \
            Be strict. Return a JSON object with fields: score (integer) and \
            feedback (string explaining what matches and what is missing).""")
    @UserMessage("""
            Prompt: {prompt}
            Image: {imageBytes}
            Rate the image adherence to the prompt.""")
    RatingResult rate(String prompt, byte[] imageBytes);
}
