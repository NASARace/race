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