//--- Cesium CustomProperties

function DynamicProperty(value) {
    this.v = value;
    this.isConstant = false;
    this.definitionChanged = new Cesium.Event(); // create only one
}

DynamicProperty.prototype.getValue = function(time, result) {
    return this.v;
};

DynamicProperty.prototype.setValue = function(value) {
    this.v = value;
    this.definitionChanged.raiseEvent(this);
};