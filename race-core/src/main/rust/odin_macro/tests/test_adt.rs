#![allow(unused)]

use std::error::Error;
use odin_macro::{define_algebraic_type, match_algebraic_type};


#[derive(Debug)] struct GpsData { lat: f64, lon: f64 }
#[derive(Debug)] struct ThermoData { temp: f64 }

#[derive(Debug)] 
struct Record<T> {
    id: u64,
    data: T,
}

define_algebraic_type! {
    SensorRecord = Record<GpsData> | Record<ThermoData>
}

#[test]
fn test_match()->Result<(),Box<dyn Error>> {

    //let rec = Record{ id: 1, data: ThermoData{temp:42.0}};
    let rec = Record{ id: 2, data: GpsData{lat:37.0,lon:-121.0}};
    let mut sr = SensorRecord::from(rec);

    println!("matching sr = {:?}", sr);

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