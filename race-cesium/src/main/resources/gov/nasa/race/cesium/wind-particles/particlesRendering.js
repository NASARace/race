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
import * as Util from "./windUtils.js";
import { CustomPrimitive } from "./customPrimitive.js";

export class ParticlesRendering {

    constructor(context, data, userInput, viewerParameters, particlesComputing) {
        this.createRenderingTextures(context, data);
        this.createRenderingFramebuffers(context);
        this.createRenderingPrimitives(context, data, userInput, viewerParameters, particlesComputing);
    }

    createRenderingTextures(context, data) {
        const colorTextureOptions = {
            context: context,
            width: context.drawingBufferWidth,
            height: context.drawingBufferHeight,
            pixelFormat: Cesium.PixelFormat.RGBA,
            pixelDatatype: Cesium.PixelDatatype.UNSIGNED_BYTE
        };
        const depthTextureOptions = {
            context: context,
            width: context.drawingBufferWidth,
            height: context.drawingBufferHeight,
            pixelFormat: Cesium.PixelFormat.DEPTH_COMPONENT,
            pixelDatatype: Cesium.PixelDatatype.UNSIGNED_INT
        };

        this.textures = {
            segmentsColor: Util.createTexture(colorTextureOptions),
            segmentsDepth: Util.createTexture(depthTextureOptions),

            currentTrailsColor: Util.createTexture(colorTextureOptions),
            currentTrailsDepth: Util.createTexture(depthTextureOptions),

            nextTrailsColor: Util.createTexture(colorTextureOptions),
            nextTrailsDepth: Util.createTexture(depthTextureOptions),
        };
    }

    createRenderingFramebuffers(context) {
        this.framebuffers = {
            segments: Util.createFramebuffer(context, this.textures.segmentsColor, this.textures.segmentsDepth),
            currentTrails: Util.createFramebuffer(context, this.textures.currentTrailsColor, this.textures.currentTrailsDepth),
            nextTrails: Util.createFramebuffer(context, this.textures.nextTrailsColor, this.textures.nextTrailsDepth)
        }
    }

    createSegmentsGeometry(userInput) {
        const repeatVertex = 6;

        var st = [];
        for (var s = 0; s < userInput.particlesTextureSize; s++) {
            for (var t = 0; t < userInput.particlesTextureSize; t++) {
                for (var i = 0; i < repeatVertex; i++) {
                    st.push(s / userInput.particlesTextureSize);
                    st.push(t / userInput.particlesTextureSize);
                }
            }
        }
        st = new Float32Array(st);

        var normal = [];
        const pointToUse = [-1, 0, 1];
        const offsetSign = [-1, 1];
        for (var i = 0; i < userInput.maxParticles; i++) {
            for (var j = 0; j < pointToUse.length; j++) {
                for (var k = 0; k < offsetSign.length; k++) {
                    normal.push(pointToUse[j]);
                    normal.push(offsetSign[k]);
                    normal.push(0);
                }
            }
        }
        normal = new Float32Array(normal);

        const indexSize = 12 * userInput.maxParticles;
        var vertexIndexes = new Uint32Array(indexSize);
        for (var i = 0, j = 0, vertex = 0; i < userInput.maxParticles; i++) {
            vertexIndexes[j++] = vertex + 0;
            vertexIndexes[j++] = vertex + 1;
            vertexIndexes[j++] = vertex + 2;

            vertexIndexes[j++] = vertex + 2;
            vertexIndexes[j++] = vertex + 1;
            vertexIndexes[j++] = vertex + 3;

            vertexIndexes[j++] = vertex + 2;
            vertexIndexes[j++] = vertex + 4;
            vertexIndexes[j++] = vertex + 3;

            vertexIndexes[j++] = vertex + 4;
            vertexIndexes[j++] = vertex + 3;
            vertexIndexes[j++] = vertex + 5;

            vertex += repeatVertex;
        }

        var geometry = new Cesium.Geometry({
            attributes: new Cesium.GeometryAttributes({
                st: new Cesium.GeometryAttribute({
                    componentDatatype: Cesium.ComponentDatatype.FLOAT,
                    componentsPerAttribute: 2,
                    values: st
                }),
                normal: new Cesium.GeometryAttribute({
                    componentDatatype: Cesium.ComponentDatatype.FLOAT,
                    componentsPerAttribute: 3,
                    values: normal
                }),
            }),
            indices: vertexIndexes
        });

        return geometry;
    }

    createRenderingPrimitives(context, data, userInput, viewerParameters, particlesComputing) {
        const that = this;
        const minimum = new Cesium.Cartesian3(data.lon.min, data.lat.min, data.lev.min);
        const maximum = new Cesium.Cartesian3(data.lon.max, data.lat.max, data.lev.max);

        const dimension = new Cesium.Cartesian3(data.dimensions.lon, data.dimensions.lat, data.dimensions.lev);
        const interval = new Cesium.Cartesian3(
            (maximum.x - minimum.x) / (dimension.x - 1),
            (maximum.y - minimum.y) / (dimension.y - 1),
            dimension.z > 1 ? (maximum.z - minimum.z) / (dimension.z - 1) : 1.0
        );

        const clr = new Cesium.Cartesian4( userInput.color.red, userInput.color.green, userInput.color.blue, userInput.color.alpha);

        this.primitives = {
            segments: new CustomPrimitive({
                commandType: 'Draw',
                attributeLocations: {
                    st: 0,
                    normal: 1
                },
                geometry: this.createSegmentsGeometry(userInput),
                primitiveType: Cesium.PrimitiveType.TRIANGLES,
                uniformMap: {
                    previousParticlesPosition: function() {
                        return particlesComputing.particlesTextures.previousParticlesPosition;
                    },
                    currentParticlesPosition: function() {
                        return particlesComputing.particlesTextures.currentParticlesPosition;
                    },
                    postProcessingPosition: function() {
                        return particlesComputing.particlesTextures.postProcessingPosition;
                    },
                    aspect: function() {
                        return context.drawingBufferWidth / context.drawingBufferHeight;
                    },
                    pixelSize: function() {
                        return viewerParameters.pixelSize;
                    },
                    lineWidth: function() {
                        return userInput.lineWidth;
                    },
                    particleHeight: function() {
                        return userInput.particleHeight;
                    },
                    minimum: function() {
                        return minimum;
                    },
                    maximum: function() {
                        return maximum;
                    },

                    dimension: function() {
                        return dimension;
                    },
                    interval: function() {
                        return interval;
                    },
                    H: function() {
                        return particlesComputing.windTextures.H;
                    },
                    color: function() {
                        return clr;
                    }
                },
                vertexShaderSource: new Cesium.ShaderSource({
                    sources: [Util.loadText('wind-particles/glsl/segmentDraw.vert')]
                }),
                fragmentShaderSource: new Cesium.ShaderSource({
                    sources: [Util.loadText('wind-particles/glsl/segmentDraw.frag')]
                }),
                rawRenderState: Util.createRawRenderState({
                    // undefined value means let Cesium deal with it
                    viewport: undefined,
                    depthTest: {
                        enabled: true
                    },
                    depthMask: true
                }),
                framebuffer: this.framebuffers.segments,
                autoClear: true
            }),

            trails: new CustomPrimitive({
                commandType: 'Draw',
                attributeLocations: {
                    position: 0,
                    st: 1
                },
                geometry: Util.getFullscreenQuad(),
                primitiveType: Cesium.PrimitiveType.TRIANGLES,
                uniformMap: {
                    segmentsColorTexture: function() {
                        return that.textures.segmentsColor;
                    },
                    segmentsDepthTexture: function() {
                        return that.textures.segmentsDepth;
                    },
                    currentTrailsColor: function() {
                        return that.framebuffers.currentTrails.getColorTexture(0);
                    },
                    trailsDepthTexture: function() {
                        return that.framebuffers.currentTrails.depthTexture;
                    },
                    fadeOpacity: function() {
                        return userInput.fadeOpacity;
                    }
                },
                // prevent Cesium from writing depth because the depth here should be written manually
                vertexShaderSource: new Cesium.ShaderSource({
                    defines: ['DISABLE_GL_POSITION_LOG_DEPTH'],
                    sources: [Util.loadText('wind-particles/glsl/fullscreen.vert')]
                }),
                fragmentShaderSource: new Cesium.ShaderSource({
                    defines: ['DISABLE_LOG_DEPTH_FRAGMENT_WRITE'],
                    sources: [Util.loadText('wind-particles/glsl/trailDraw.frag')]
                }),
                rawRenderState: Util.createRawRenderState({
                    viewport: undefined,
                    depthTest: {
                        enabled: true,
                        func: Cesium.DepthFunction.ALWAYS // always pass depth test for full control of depth information
                    },
                    depthMask: true
                }),
                framebuffer: this.framebuffers.nextTrails,
                autoClear: true,
                preExecute: function() {
                    // swap framebuffers before binding
                    var temp;
                    temp = that.framebuffers.currentTrails;
                    that.framebuffers.currentTrails = that.framebuffers.nextTrails;
                    that.framebuffers.nextTrails = temp;

                    // keep the framebuffers up to date
                    that.primitives.trails.commandToExecute.framebuffer = that.framebuffers.nextTrails;
                    that.primitives.trails.clearCommand.framebuffer = that.framebuffers.nextTrails;
                }
            }),

            screen: new CustomPrimitive({
                commandType: 'Draw',
                attributeLocations: {
                    position: 0,
                    st: 1
                },
                geometry: Util.getFullscreenQuad(),
                primitiveType: Cesium.PrimitiveType.TRIANGLES,
                uniformMap: {
                    trailsColorTexture: function() {
                        return that.framebuffers.nextTrails.getColorTexture(0);
                    },
                    trailsDepthTexture: function() {
                        return that.framebuffers.nextTrails.depthTexture;
                    }
                },
                // prevent Cesium from writing depth because the depth here should be written manually
                vertexShaderSource: new Cesium.ShaderSource({
                    defines: ['DISABLE_GL_POSITION_LOG_DEPTH'],
                    sources: [Util.loadText('wind-particles/glsl/fullscreen.vert')]
                }),
                fragmentShaderSource: new Cesium.ShaderSource({
                    defines: ['DISABLE_LOG_DEPTH_FRAGMENT_WRITE'],
                    sources: [Util.loadText('wind-particles/glsl/screenDraw.frag')]
                }),
                rawRenderState: Util.createRawRenderState({
                    viewport: undefined,
                    depthTest: {
                        enabled: false
                    },
                    depthMask: true,
                    blending: {
                        enabled: true
                    }
                }),
                framebuffer: undefined // undefined value means let Cesium deal with it
            })
        };
    }

    forEachPrimitive(func) {
        func(this.primitives.segments);
        func(this.primitives.trails);
        func(this.primitives.screen);
    }

    updateSegments (context,userInput) {
        let segments = this.primitives.segments;
        let geometry = this.createSegmentsGeometry(userInput);
        segments.geometry = geometry;

        if (segments.commandToExecute) {
            segments.commandToExecute.vertexArray = Cesium.VertexArray.fromGeometry({
                context: context,
                geometry: geometry,
                attributeLocations: segments.attributeLocations,
                bufferUsage: Cesium.BufferUsage.STATIC_DRAW,
            });
        }
    }

    updateColor (color) {
        const clr = new Cesium.Cartesian4( color.red, color.green, color.blue, color.alpha);

        this.primitives.segments.uniformMap.color = function() {
            return clr;
        };
    }

    updateUserInputUniforms (userInput) {
        let primitives = this.primitives;

        let map = primitives.segments.uniformMap;
        const lineWidth = userInput.lineWidth;
        map.lineWidth = function() {
            return lineWidth;
        };
        const particleHeight = userInput.particleHeight;
        map.particleHeight = function() {
            return particleHeight;
        };
        const color = userInput.color;
        const clr = new Cesium.Cartesian4( color.red, color.green, color.blue, color.alpha);
        map.color = function() {
            return clr;
        };

        map = primitives.trails.uniformMap;
        const fadeOpacity = userInput.fadeOpacity;
        map.fadeOpacity = function() {
            return fadeOpacity;
        };
    }
}