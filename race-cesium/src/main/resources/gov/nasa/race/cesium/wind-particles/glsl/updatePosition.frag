//#version 300 es

uniform sampler2D currentParticlesPosition; // (lon, lat, lev)
uniform sampler2D particlesSpeed; // (u, v, w, norm) Unit converted to degrees of longitude and latitude 

// TODO - should we just ignore w and interpolate the height from lonLat and H texture ?

in vec2 v_textureCoordinates;
out vec4 fragColor;

void main() {
    // texture coordinate must be normalized
    vec3 lonLatLev = texture(currentParticlesPosition, v_textureCoordinates).rgb;
    vec3 speed = texture(particlesSpeed, v_textureCoordinates).rgb;
    vec3 nextParticle = lonLatLev + speed;

    fragColor = vec4(nextParticle, 0.0);
}