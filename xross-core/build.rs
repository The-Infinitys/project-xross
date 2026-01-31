use std::{env, fs, path::Path};
use xross_metadata::{FieldMetadata, MethodMetadata, StructMetadata, XrossCombinedMetadata};

fn main() {
    println!("cargo:rerun-if-changed=build.rs");

    let out_dir = env::var("OUT_DIR").expect("OUT_DIR not set");
    let dest_path = Path::new(&out_dir).join("xross_metadata.json");

    let mut combined_structs: Vec<StructMetadata> = Vec::new();
    let mut combined_methods: Vec<MethodMetadata> = Vec::new();

    let out_dir_path = Path::new(&out_dir);

    for entry in fs::read_dir(out_dir_path).expect("Failed to read OUT_DIR") {
        let entry = entry.expect("Failed to read directory entry");
        let path = entry.path();

        if path.is_file() {
            if path.file_name().map_or(false, |name| name.to_string_lossy().ends_with("_struct_metadata.json")) {
                let content = fs::read_to_string(&path).expect(&format!("Failed to read metadata file: {:?}", path));
                let struct_meta: StructMetadata = serde_json::from_str(&content).expect(&format!("Failed to parse StructMetadata from {:?}", path));
                combined_structs.push(struct_meta);
            } else if path.file_name().map_or(false, |name| name.to_string_lossy().ends_with("_method_metadata.json")) {
                let content = fs::read_to_string(&path).expect(&format!("Failed to read metadata file: {:?}", path));
                let method_meta_list: Vec<MethodMetadata> = serde_json::from_str(&content).expect(&format!("Failed to parse MethodMetadata list from {:?}", path));
                combined_methods.extend(method_meta_list);
            }
        }
    }

    let final_combined_metadata = XrossCombinedMetadata {
        structs: combined_structs,
        methods: combined_methods,
    };

    let final_json = serde_json::to_string_pretty(&final_combined_metadata).expect("Failed to serialize combined metadata");
    fs::write(&dest_path, final_json).expect("Failed to write combined metadata to file");

    println!("cargo:warning=xross: Combined metadata written to {:?}", dest_path);
}