#![allow(unused)]

use std::error::Error;
use odin_macro::{define_algebraic_type, match_algebraic_type};


#[derive(Debug,Clone)] struct GpsData { lat: f64, lon: f64 }
#[derive(Debug,Clone)] struct ThermoData { temp: f64 }

#[derive(Debug,Clone)] 
struct Record<T> {
    id: u64,
    device: u64,
    data: T,
}

define_algebraic_type! {
    SensorRecord: Clone = Record<GpsData> | Record<ThermoData>

    fn id (&self)->u64 { __.id } // '__' use causes variant expansion
    pub fn device (&self)->u64 { __.device }
    pub fn describe ()->&'static str { "this is my sensor record" } // no variant expansion
}

/*
  expanded into:

    #derive(Clone)

    enum SensorRecord {
        Record_GpsData_ ( Record<GpsData> ),
        Record_ThermoData_ ( Record<ThermoData> )
    }

    impl SensorRecord {
        fn id (&self)->u64 {
            match self {
                Self::Record_GpsData_(__) => { __.id }
                Self::Record_ThermoData_(__) => { __.id } 
            }
        }
        pub fn device (&self)->u64 {
            match self {
                Self::Record_GpsData_(__) => { __.device }
                Self::Record_ThermoData_(__) => { __.device } 
            }
        }
    }
    impl From<Record<GpsData>> for SensorRecord{
        fn from(v : Record<GpsData>)->Self { SensorRecord :: RecordᐸGpsDataᐳ(v) }
    }
    impl From<Record<ThermoData>> for SensorRecord {
        fn from(v : Record<ThermoData>) -> Self { SensorRecord :: RecordᐸThermoDataᐳ(v) }
    }
    impl Debug for SensorRecord { .. }
 */

#[test]
fn test_match()->Result<(),Box<dyn Error>> {

    //let rec = Record{ id: 1, data: ThermoData{temp:42.0}};
    let rec = Record{ id: 2, device: 42, data: GpsData{lat:37.0,lon:-121.0}};
    let mut sr = SensorRecord::from(rec);

    println!("matching sr = {:?}", sr);

    println!("device = {}", sr.device());

    println!("and the description is: {}", SensorRecord::describe());

    match_algebraic_type! { sr: SensorRecord as 
        mut Record<GpsData> => {
            sr.id += 1;
            println!("it's a Gps record and I mutated it: {sr:?}");
        }
        //Record<ThermoData> => println!("it's a Thermo record: {sr:?}"),
        _ => println!("it's some record I don't care about")
    }

    Ok(())
}