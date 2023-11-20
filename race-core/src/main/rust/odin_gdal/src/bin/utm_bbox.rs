use lazy_static::lazy_static;
use structopt::StructOpt;

use odin_gdal::{transform_latlon_to_utm_bounds, transform_utm_to_latlon_bounds};

#[derive(StructOpt)]
struct CliOpts {
    /// west boundary in degrees
    #[structopt(long,short,allow_hyphen_values=true)]
    west: f64,

    /// south boundary in degrees
    #[structopt(long,short,allow_hyphen_values=true)]
    south: f64,

    /// east boundary in degrees
    #[structopt(long,short,allow_hyphen_values=true)]
    east: f64,

    /// north boundary in degrees
    #[structopt(long,short,allow_hyphen_values=true)]
    north: f64,

    /// do we want the interior or exterior target rectangle
    #[structopt(short,long)]
    interior: bool,

    /// utm zone
    #[structopt(long)]
    zone: Option<u32>,

    /// southern hemisphere zone
    #[structopt(long)]
    is_south: bool,

    // reverse transformation (UTM -> epsg:4326 (lat,lon))
    #[structopt(short="r",long)]
    utm_to_latlon: bool,
}

lazy_static! {
    static ref ARGS: CliOpts = CliOpts::from_args();
}

fn main() {
     if ARGS.utm_to_latlon {
         if let Some(utm_zone) = ARGS.zone {
             let res = transform_utm_to_latlon_bounds(ARGS.west, ARGS.south, ARGS.east, ARGS.north, ARGS.interior, utm_zone, ARGS.is_south);
             match res {
                 Ok((x_min, y_min, x_max, y_max)) => {
                     println!("{} lat/lon bounding box for UTM zone {}{}",
                              if ARGS.interior { "interior" } else { "exterior" },
                              if ARGS.is_south {"s"} else {"n"},
                              utm_zone);
                     println!("  west:  {:15.3} [m] -> {:11.6} [°]", ARGS.west , x_min);
                     println!("  south: {:15.3} [m] -> {:11.6} [°]", ARGS.south, y_min);
                     println!("  east:  {:15.3} [m] -> {:11.6} [°]", ARGS.east , x_max);
                     println!("  north: {:15.3} [m] -> {:11.6} [°]", ARGS.north, y_max);
                 }
                 Err(e) =>  println!("failed to compute latlon bounding box: {:?}", e)
             }
         }

     } else {
         let res = transform_latlon_to_utm_bounds(ARGS.west, ARGS.south, ARGS.east, ARGS.north, ARGS.interior, ARGS.zone, ARGS.is_south);

         match res {
             Ok((x_min,y_min,x_max,y_max, utm_zone)) => {
                 println!("{} UTM bounding box in zone {}{}",
                          if ARGS.interior { "interior" } else { "exterior" },
                          if ARGS.is_south {"s"} else {"n"},
                          utm_zone);
                 println!("  west:  {:11.6} [°] -> {:15.3} [m] ", ARGS.west , x_min);
                 println!("  south: {:11.6} [°] -> {:15.3} [m] ", ARGS.south, y_min);
                 println!("  east:  {:11.6} [°] -> {:15.3} [m] ", ARGS.east , x_max);
                 println!("  north: {:11.6} [°] -> {:15.3} [m] ", ARGS.north, y_max);
             }
             Err(e) => println!("failed to compute UTM bounding box: {:?}", e)
         }
     }
}
