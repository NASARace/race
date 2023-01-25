
export function loadText(filePath) {
    var request = new XMLHttpRequest();
    request.open('GET', filePath, false);
    request.send();
    return request.responseText;
}

export function setParticlesTextureSize(userInput) {
    // make sure maxParticles is exactly the square of particlesTextureSize
    userInput.particlesTextureSize = Math.ceil(Math.sqrt(userInput.maxParticles));
    userInput.maxParticles = userInput.particlesTextureSize * userInput.particlesTextureSize;
}

export function getFullscreenQuad() {
    var fullscreenQuad = new Cesium.Geometry({
        attributes: new Cesium.GeometryAttributes({
            position: new Cesium.GeometryAttribute({
                componentDatatype: Cesium.ComponentDatatype.FLOAT,
                componentsPerAttribute: 3,
                //  v3----v2
                //  |     |
                //  |     |
                //  v0----v1
                values: new Float32Array([-1, -1, 0, // v0
                    1, -1, 0, // v1
                    1, 1, 0, // v2
                    -1, 1, 0, // v3
                ])
            }),
            st: new Cesium.GeometryAttribute({
                componentDatatype: Cesium.ComponentDatatype.FLOAT,
                componentsPerAttribute: 2,
                values: new Float32Array([
                    0, 0,
                    1, 0,
                    1, 1,
                    0, 1,
                ])
            })
        }),
        indices: new Uint32Array([3, 2, 0, 0, 2, 1])
    });
    return fullscreenQuad;
}

export function createTexture(options, typedArray) {
    if (Cesium.defined(typedArray)) {
        // typed array needs to be passed as source option, this is required by Cesium.Texture
        var source = {};
        source.arrayBufferView = typedArray;
        options.source = source;
    }

    var texture = new Cesium.Texture(options);
    return texture;
}

export function createFramebuffer(context, colorTexture, depthTexture) {
    var framebuffer = new Cesium.Framebuffer({
        context: context,
        colorTextures: [colorTexture],
        depthTexture: depthTexture
    });
    return framebuffer;
}

export function createRawRenderState(options) {
    var translucent = true;
    var closed = false;
    var existing = {
        viewport: options.viewport,
        depthTest: options.depthTest,
        depthMask: options.depthMask,
        blending: options.blending
    };

    var rawRenderState = Cesium.Appearance.getDefaultRenderState(translucent, closed, existing);
    return rawRenderState;
}

export function viewRectangleToLonLatRange(viewRectangle) {
    var range = {};

    var postiveWest = Cesium.Math.mod(viewRectangle.west, Cesium.Math.TWO_PI);
    var postiveEast = Cesium.Math.mod(viewRectangle.east, Cesium.Math.TWO_PI);
    var width = viewRectangle.width;

    var longitudeMin;
    var longitudeMax;
    if (width > Cesium.Math.THREE_PI_OVER_TWO) {
        longitudeMin = 0.0;
        longitudeMax = Cesium.Math.TWO_PI;
    } else {
        if (postiveEast - postiveWest < width) {
            longitudeMin = postiveWest;
            longitudeMax = postiveWest + width;
        } else {
            longitudeMin = postiveWest;
            longitudeMax = postiveEast;
        }
    }

    range.lon = {
        min: Cesium.Math.toDegrees(longitudeMin),
        max: Cesium.Math.toDegrees(longitudeMax)
    }

    var south = viewRectangle.south;
    var north = viewRectangle.north;
    var height = viewRectangle.height;

    var extendHeight = height > Cesium.Math.PI / 12 ? height / 2 : 0;
    var extendedSouth = Cesium.Math.clampToLatitudeRange(south - extendHeight);
    var extendedNorth = Cesium.Math.clampToLatitudeRange(north + extendHeight);

    // extend the bound in high latitude area to make sure it can cover all the visible area
    if (extendedSouth < -Cesium.Math.PI_OVER_THREE) {
        extendedSouth = -Cesium.Math.PI_OVER_TWO;
    }
    if (extendedNorth > Cesium.Math.PI_OVER_THREE) {
        extendedNorth = Cesium.Math.PI_OVER_TWO;
    }

    range.lat = {
        min: Cesium.Math.toDegrees(extendedSouth),
        max: Cesium.Math.toDegrees(extendedNorth)
    }

    return range;
}


export function randomizeParticles(data, maxParticles, viewerParameters) {
    var array = new Float32Array(4 * maxParticles);
    for (var i = 0; i < maxParticles; i++) {
        const j = 4*i;
        array[j] = Cesium.Math.randomBetween(viewerParameters.lonRange.x, viewerParameters.lonRange.y);
        array[j + 1] = Cesium.Math.randomBetween(viewerParameters.latRange.x, viewerParameters.latRange.y);
        array[j + 2] = Cesium.Math.randomBetween(data.lev.min, data.lev.max);
        array[j + 3] = 0.0;
    }
    return array;
}