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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

@RegisterRestClient(configKey = "comfyui")
public interface ComfyUIRestClient {

    @POST
    @Path("/prompt")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Object> submitPrompt(Map<String, Object> body);

    @GET
    @Path("/history/{promptId}")
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Object> getHistory(@PathParam("promptId") String promptId);

    @GET
    @Path("/view")
    @Produces("image/png")
    byte[] viewImage(@QueryParam("filename") String filename,
                     @QueryParam("subfolder") @DefaultValue("") String subfolder,
                     @QueryParam("type") @DefaultValue("output") String type);
}
