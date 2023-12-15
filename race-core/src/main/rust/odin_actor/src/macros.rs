
#[macro_export]
macro_rules! spawn_actor {
    { $asys:ident, $aname:expr, $astate:expr } =>
    { $asys.spawn_actor( $asys.new_actor( $aname, $astate, 8)) };

    { $asys:ident, $aname:expr, $astate:expr, $abounds:expr } =>
    { $asys.spawn_actor( $asys.new_actor( $aname, $astate, $abounds)) };
}
