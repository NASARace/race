/*
 * Copyright (c) 2023, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { ParticlesComputing } from "./particlesComputing.js";
import { ParticlesRendering } from "./particlesRendering.js";

export class ParticleSystem {
    constructor(context, data, userInput, viewerParameters) {
        this.context = context;
        this.data = data;
        this.userInput = {...userInput};
        this.viewerParameters = {...viewerParameters};

        this.particlesComputing = new ParticlesComputing(
            this.context, this.data,
            this.userInput, this.viewerParameters
        );
        this.particlesRendering = new ParticlesRendering(
            this.context, this.data,
            this.userInput, this.viewerParameters,
            this.particlesComputing
        );
    }

    canvasResize(context) {
        this.particlesComputing.destroyParticlesTextures();
        Object.keys(this.particlesComputing.windTextures).forEach((key) => {
            this.particlesComputing.windTextures[key].destroy();
        });

        Object.keys(this.particlesRendering.framebuffers).forEach((key) => {
            this.particlesRendering.framebuffers[key].destroy();
        });

        this.context = context;
        this.particlesComputing = new ParticlesComputing(
            this.context, this.data,
            this.userInput, this.viewerParameters
        );
        this.particlesRendering = new ParticlesRendering(
            this.context, this.data,
            this.userInput, this.viewerParameters,
            this.particlesComputing
        );
    }

    forEachPrimitive(func) {
        this.particlesComputing.forEachPrimitive(func);
        this.particlesRendering.forEachPrimitive(func);
    }

    release() {
        this.particlesComputing.destroyParticlesTextures();
        // TODO - frameBuffers and particlesRendering resources ?
    }

    clearFramebuffers() {
        var clearCommand = new Cesium.ClearCommand({
            color: new Cesium.Color(0.0, 0.0, 0.0, 0.0),
            depth: 1.0,
            framebuffer: undefined,
            pass: Cesium.Pass.OPAQUE
        });

        Object.keys(this.particlesRendering.framebuffers).forEach((key) => {
            clearCommand.framebuffer = this.particlesRendering.framebuffers[key];
            clearCommand.execute(this.context);
        });
    }

    refreshParticles() {
        this.clearFramebuffers();
        this.particlesComputing.refreshParticles(this.context, this.data, this.userInput, this.viewerParameters);
        this.particlesRendering.updateSegments(this.context, this.userInput);
    }

    applyUserInput(userInput) {
        this.userInput = {...userInput};

        this.particlesComputing.updateUserInputUniforms(userInput);
        this.particlesRendering.updateUserInputUniforms(userInput);
        this.refreshParticles();
    }

    applyViewerParameters(viewerParameters) {
        this.viewerParameters = {...viewerParameters};
        this.refreshParticles();
    }
}