//! Build script for Atmosphere Android bindings
//! 
//! This generates the UniFFI scaffolding code from the UDL file.

fn main() {
    // Generate the UniFFI scaffolding from the UDL file
    uniffi::generate_scaffolding("src/atmosphere.udl")
        .expect("Failed to generate UniFFI scaffolding");
}
