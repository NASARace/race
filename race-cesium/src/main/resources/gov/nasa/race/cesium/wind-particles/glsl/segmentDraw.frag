//#version 300 es

uniform vec4 color;
out vec4 fragColor;

void main() {
    //const vec4 white = vec4(1.0);
    //const vec4 clr = vec4(0.0, 1.0, 1.0, 1.0);
    //fragColor = clr;

    fragColor = color;
}