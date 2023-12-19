/*
 * Copyright (c) 2016, United States Government, as represented by the
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
export class CustomPrimitive {

    constructor(options) {
        this.commandType = options.commandType;

        this.geometry = options.geometry;
        this.attributeLocations = options.attributeLocations;
        this.primitiveType = options.primitiveType;

        this.uniformMap = options.uniformMap;

        this.vertexShaderSource = options.vertexShaderSource;
        this.fragmentShaderSource = options.fragmentShaderSource;

        this.rawRenderState = options.rawRenderState;
        this.framebuffer = options.framebuffer;

        this.outputTexture = options.outputTexture;

        this.autoClear = Cesium.defaultValue(options.autoClear, false);
        this.preExecute = options.preExecute;

        this.show = true;
        this.commandToExecute = undefined;
        this.clearCommand = undefined;
        if (this.autoClear) {
            this.clearCommand = new Cesium.ClearCommand({
                color: new Cesium.Color(0.0, 0.0, 0.0, 0.0),
                depth: 1.0,
                framebuffer: this.framebuffer,
                pass: Cesium.Pass.OPAQUE
            });
        }
    }

    createCommand(context) {
        switch (this.commandType) {
            case 'Draw':
                {
                    var vertexArray = Cesium.VertexArray.fromGeometry({
                        context: context,
                        geometry: this.geometry,
                        attributeLocations: this.attributeLocations,
                        bufferUsage: Cesium.BufferUsage.STATIC_DRAW,
                    });

                    var shaderProgram = Cesium.ShaderProgram.fromCache({
                        context: context,
                        attributeLocations: this.attributeLocations,
                        vertexShaderSource: this.vertexShaderSource,
                        fragmentShaderSource: this.fragmentShaderSource
                    });

                    var renderState = Cesium.RenderState.fromCache(this.rawRenderState);
                    return new Cesium.DrawCommand({
                        owner: this,
                        vertexArray: vertexArray,
                        primitiveType: this.primitiveType,
                        uniformMap: this.uniformMap,
                        modelMatrix: Cesium.Matrix4.IDENTITY,
                        shaderProgram: shaderProgram,
                        framebuffer: this.framebuffer,
                        renderState: renderState,
                        pass: Cesium.Pass.OPAQUE
                    });
                }
            case 'Compute':
                {
                    return new Cesium.ComputeCommand({
                        owner: this,
                        fragmentShaderSource: this.fragmentShaderSource,
                        uniformMap: this.uniformMap,
                        outputTexture: this.outputTexture,
                        persists: true
                    });
                }
        }
    }

    setGeometry(context, geometry) {
        this.geometry = geometry;
        var vertexArray = Cesium.VertexArray.fromGeometry({
            context: context,
            geometry: this.geometry,
            attributeLocations: this.attributeLocations,
            bufferUsage: Cesium.BufferUsage.STATIC_DRAW,
        });
        this.commandToExecute.vertexArray = vertexArray;
    }

    update(frameState) {
        if (!this.show) {
            return;
        }

        if (!Cesium.defined(this.commandToExecute)) {
            this.commandToExecute = this.createCommand(frameState.context);
        }

        if (Cesium.defined(this.preExecute)) {
            this.preExecute();
        }

        if (Cesium.defined(this.clearCommand)) {
            frameState.commandList.push(this.clearCommand);
        }
        frameState.commandList.push(this.commandToExecute);
    }

    isDestroyed() {
        return false;
    }

    destroy() {
        if (Cesium.defined(this.commandToExecute)) {
            this.commandToExecute.shaderProgram = this.commandToExecute.shaderProgram && this.commandToExecute.shaderProgram.destroy();
        }
        return Cesium.destroyObject(this);
    }
}