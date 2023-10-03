use lazy_static::lazy_static;
use structopt::StructOpt;

use odin_gdal::transform_latlon_to_utm_bounds;

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
}

lazy_static! {
    static ref ARGS: CliOpts = CliOpts::from_args();
}

fn main() {
     let result = transform_latlon_to_utm_bounds(
         ARGS.west,
         ARGS.south,
         ARGS.east,
         ARGS.north,
         ARGS.interior, ARGS.zone);

    match result {
        Ok((x_min,y_min,x_max,y_max, utm_zone)) => {
            println!("{} UTM bounding box in zone {}", if ARGS.interior { "interior" } else { "exterior" }, utm_zone);
            println!("  west:  {:15.3}m  {:10.4}°", x_min, ARGS.west);
            println!("  south: {:15.3}m  {:10.4}°", y_min, ARGS.south);
            println!("  east:  {:15.3}m  {:10.4}°", x_max, ARGS.east);
            println!("  north: {:15.3}m  {:10.4}°", y_max, ARGS.north);
        }
        Err(e) =>
            println!("failed to compute bounding box: {:?}", e)
    }
}
