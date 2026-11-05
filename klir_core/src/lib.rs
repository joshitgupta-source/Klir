use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use jwalk::WalkDir;
use rayon::prelude::*;
use std::collections::{HashMap, HashSet};
use std::fmt::Write;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Component, Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

// JunkType mapping for Kotlin:
// 0 = THUMBNAILS
// 1 = INSTALLERS
// 2 = TEMP_FILES
// 3 = EMPTY_DIRECTORIES
// 4 = LARGE_FILES
// 5 = STALE_FILES
// 6 = MESSENGER_MEDIA
// 7 = DUPLICATE_FILES
// 8 = APP_CORPSES (CorpseFinder)

const LARGE_FILE_THRESHOLD: u64 = 104_857_600; // 100 MB
const STALE_FILE_MIN_SIZE: u64 = 10_485_760;   // 10 MB minimum for stale files
const STALE_AGE_SECONDS: u64 = 15_552_000;     // 180 days
const DUPLICATE_MIN_SIZE: u64 = 65_536;        // 64 KB minimum for duplicate check
const PARTIAL_HASH_BYTES: usize = 4096;        // 4 KB header hash

#[no_mangle]
pub unsafe extern "C" fn Java_com_joshit_klir_StorageCleanerEngine_scanNative(
    mut env: JNIEnv,
    _class: JClass,
    root_path: JString,
    excluded_paths_raw: JString,
    installed_packages_raw: JString,
    check_thumbnails: jboolean,
    check_apks: jboolean,
    check_temp: jboolean,
    check_empty_dirs: jboolean,
    check_large: jboolean,
    check_stale: jboolean,
    check_messenger: jboolean,
    check_duplicates: jboolean,
    check_corpses: jboolean,
) -> jstring {
    let root: String = match env.get_string(&root_path) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let excluded_raw: String = env
        .get_string(&excluded_paths_raw)
        .map(|s| s.into())
        .unwrap_or_default();

    let excluded_list: Vec<&str> = excluded_raw
        .lines()
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect();

    let installed_raw: String = env
        .get_string(&installed_packages_raw)
        .map(|s| s.into())
        .unwrap_or_default();

    let installed_packages: HashSet<String> = installed_raw
        .lines()
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty())
        .collect();

    let thumbnails_on = check_thumbnails != 0;
    let apks_on = check_apks != 0;
    let temp_on = check_temp != 0;
    let empty_dirs_on = check_empty_dirs != 0;
    let large_on = check_large != 0;
    let stale_on = check_stale != 0;
    let messenger_on = check_messenger != 0;
    let duplicates_on = check_duplicates != 0;
    let corpses_on = check_corpses != 0;

    let now_epoch = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    let mut output = String::with_capacity(128 * 1024);
    let mut corpse_dirs: Vec<String> = Vec::new();

    if corpses_on && installed_packages.len() >= 5 {
        let corpses = find_app_corpses(&root, &installed_packages, &excluded_list);
        for (corpse_path, size, label) in corpses {
            let _ = writeln!(output, "8|{}|{}|{}", size, label, corpse_path);
            corpse_dirs.push(corpse_path);
        }
    }

    let mut size_buckets: HashMap<u64, Vec<(PathBuf, u64)>> = HashMap::new();
    let corpse_refs: Vec<&str> = corpse_dirs.iter().map(|s| s.as_str()).collect();

    for entry in WalkDir::new(&root).skip_hidden(false) {
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };

        let path = entry.path();
        let path_str = match path.to_str() {
            Some(s) => s,
            None => continue,
        };

        if path_str.contains("/Android/data")
            || path_str.contains("/Android/obb")
            || path_str.contains("/.klir_trash")
        {
            continue;
        }

        if is_path_excluded(path_str, &excluded_list) || is_path_excluded(path_str, &corpse_refs) {
            continue;
        }

        if entry.file_type.is_dir() {
            if empty_dirs_on && path_str != root {
                if let Ok(mut read_dir) = fs::read_dir(&path) {
                    if read_dir.next().is_none() {
                        let _ = writeln!(output, "3|0|Empty folder|{}", path_str);
                    }
                }
            }
            continue;
        }

        if !entry.file_type.is_file() {
            continue;
        }

        let metadata = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };

        let file_size = metadata.len();
        if file_size == 0 {
            continue;
        }

        let modified_epoch = metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| d.as_secs())
            .unwrap_or(now_epoch);

        let ext = path
            .extension()
            .and_then(|s| s.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();
        let parent_name = path
            .parent()
            .and_then(|p| p.file_name())
            .and_then(|s| s.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();
        let file_name = path
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();

        let mut matched_specific_junk = false;

        if thumbnails_on && (parent_name == ".thumbnails" || file_name.starts_with(".thumbdata")) {
            let _ = writeln!(output, "0|{}|Thumbnail cache|{}", file_size, path_str);
            matched_specific_junk = true;
        } else if apks_on && ext == "apk" {
            let _ = writeln!(output, "1|{}|Installer package|{}", file_size, path_str);
            matched_specific_junk = true;
        } else if temp_on && matches!(ext.as_str(), "tmp" | "log" | "bak" | "temp") {
            let _ = writeln!(output, "2|{}|Temporary file|{}", file_size, path_str);
            matched_specific_junk = true;
        } else if messenger_on && is_messenger_clutter(path_str, &parent_name, &ext) {
            let label = classify_messenger_label(path_str);
            let _ = writeln!(output, "6|{}|{}|{}", file_size, label, path_str);
            matched_specific_junk = true;
        }

        if !matched_specific_junk && large_on && file_size >= LARGE_FILE_THRESHOLD {
            let _ = writeln!(output, "4|{}|Large file|{}", file_size, path_str);
        }

        if !matched_specific_junk
            && stale_on
            && file_size >= STALE_FILE_MIN_SIZE
            && now_epoch > modified_epoch
            && (now_epoch - modified_epoch) >= STALE_AGE_SECONDS
        {
            let days_old = (now_epoch - modified_epoch) / 86_400;
            let _ = writeln!(output, "5|{}|Untouched {}d|{}", file_size, days_old, path_str);
        }

        if duplicates_on && !matched_specific_junk && file_size >= DUPLICATE_MIN_SIZE {
            size_buckets
                .entry(file_size)
                .or_default()
                .push((path, modified_epoch));
        }
    }

    if duplicates_on {
        let duplicate_lines = find_duplicates_parallel(size_buckets);
        for line in duplicate_lines {
            output.push_str(&line);
        }
    }

    env.new_string(output)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}

/// Phase 4: Hierarchical Disk Map Aggregator
/// Output line format: "is_dir(1/0)|size_bytes|file_count|name|full_path"
#[no_mangle]
pub unsafe extern "C" fn Java_com_joshit_klir_StorageCleanerEngine_scanDiskMapNative(
    mut env: JNIEnv,
    _class: JClass,
    target_path: JString,
    excluded_paths_raw: JString,
) -> jstring {
    let target_str: String = match env.get_string(&target_path) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap().into_raw(),
    };

    let excluded_raw: String = env
        .get_string(&excluded_paths_raw)
        .map(|s| s.into())
        .unwrap_or_default();

    let excluded_list: Vec<&str> = excluded_raw
        .lines()
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect();

    let target_root = Path::new(&target_str);
    if !target_root.exists() {
        return env.new_string("").unwrap().into_raw();
    }

    // Map: immediate_child_name -> (is_dir, total_bytes, file_count, full_path)
    let mut buckets: HashMap<String, (bool, u64, usize, String)> = HashMap::new();

    for entry in WalkDir::new(target_root).skip_hidden(false) {
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };

        let path = entry.path();
        if path == target_root {
            continue;
        }

        let path_str = match path.to_str() {
            Some(s) => s,
            None => continue,
        };

        if path_str.contains("/Android/data")
            || path_str.contains("/Android/obb")
            || path_str.contains("/.klir_trash")
            || is_path_excluded(path_str, &excluded_list)
        {
            continue;
        }

        let rel = match path.strip_prefix(target_root) {
            Ok(r) => r,
            Err(_) => continue,
        };

        let mut comps = rel.components();
        let first_comp = match comps.next() {
            Some(Component::Normal(os_str)) => match os_str.to_str() {
                Some(s) => s.to_string(),
                None => continue,
            },
            _ => continue,
        };

        let is_nested = comps.next().is_some();
        let is_file = entry.file_type.is_file();
        let file_size = if is_file {
            entry.metadata().map(|m| m.len()).unwrap_or(0)
        } else {
            0
        };

        let bucket = buckets.entry(first_comp.clone()).or_insert_with(|| {
            let child_path = target_root.join(&first_comp);
            let child_path_str = child_path.to_string_lossy().into_owned();
            let dir_flag = is_nested || entry.file_type.is_dir();
            (dir_flag, 0u64, 0usize, child_path_str)
        });

        if is_nested || entry.file_type.is_dir() {
            bucket.0 = true;
        }

        if is_file {
            bucket.1 += file_size;
            bucket.2 += 1;
        }
    }

    let mut sorted_nodes: Vec<(String, (bool, u64, usize, String))> = buckets
        .into_iter()
        .filter(|(_, (_, size, _, _))| *size > 0)
        .collect();

    sorted_nodes.sort_by(|a, b| b.1 .1.cmp(&a.1 .1));

    let mut output = String::with_capacity(16 * 1024);
    for (name, (is_dir, size, count, full_path)) in sorted_nodes {
        let dir_flag = if is_dir { "1" } else { "0" };
        let _ = writeln!(
            output,
            "{}|{}|{}|{}|{}",
            dir_flag, size, count, name, full_path
        );
    }

    env.new_string(output)
        .unwrap_or_else(|_| env.new_string("").unwrap())
        .into_raw()
}

fn find_app_corpses(
    root: &str,
    installed_packages: &HashSet<String>,
    excluded_list: &[&str],
) -> Vec<(String, u64, String)> {
    let mut results = Vec::new();

    let container_dirs = [
        format!("{}/Android/media", root),
        format!("{}/Android/obb", root),
    ];

    for container in &container_dirs {
        if let Ok(entries) = fs::read_dir(container) {
            for entry in entries.flatten() {
                let path = entry.path();
                if !path.is_dir() {
                    continue;
                }
                let path_str = match path.to_str() {
                    Some(s) => s.to_string(),
                    None => continue,
                };
                if is_path_excluded(&path_str, excluded_list) {
                    continue;
                }
                let folder_name = match path.file_name().and_then(|n| n.to_str()) {
                    Some(n) => n.to_ascii_lowercase(),
                    None => continue,
                };

                if looks_like_package_name(&folder_name) && !installed_packages.contains(&folder_name) {
                    let (bytes, files) = compute_dir_stats(&path);
                    let label = format!("Uninstalled app ({} • {} files)", folder_name, files);
                    results.push((path_str, bytes, label));
                }
            }
        }
    }

    let known_public_folders: &[(&str, &[&str])] = &[
        ("WhatsApp", &["com.whatsapp", "com.whatsapp.w4b"]),
        ("Telegram", &["org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"]),
        ("Snapchat", &["com.snapchat.android"]),
        ("TikTok", &["com.zhiliaoapp.musically", "com.ss.android.ugc.trill"]),
        ("Viber", &["com.viber.voip"]),
        ("SHAREit", &["com.lenovo.anyshare.gps"]),
        ("Xender", &["cn.xender"]),
        ("Truecaller", &["com.truecaller"]),
        ("CapCut", &["com.lemon.lvoverseas"]),
    ];

    for (folder, pkg_candidates) in known_public_folders {
        let candidate_path = Path::new(root).join(folder);
        if candidate_path.is_dir() {
            if let Some(path_str) = candidate_path.to_str() {
                if is_path_excluded(path_str, excluded_list) {
                    continue;
                }
                let any_installed = pkg_candidates
                    .iter()
                    .any(|pkg| installed_packages.contains(*pkg));
                if !any_installed {
                    let (bytes, files) = compute_dir_stats(&candidate_path);
                    if files > 0 {
                        let label = format!("Orphaned {} folder ({} files)", folder, files);
                        results.push((path_str.to_string(), bytes, label));
                    }
                }
            }
        }
    }

    results
}

fn looks_like_package_name(name: &str) -> bool {
    let parts: Vec<&str> = name.split('.').collect();
    if parts.len() < 2 {
        return false;
    }
    matches!(
        parts[0],
        "com" | "org" | "net" | "io" | "in" | "me" | "de" | "uk" | "jp" | "ru" | "tv" | "cc" | "app" | "xyz" | "co" | "cn"
    )
}

fn compute_dir_stats(dir: &Path) -> (u64, usize) {
    let mut total_bytes = 0u64;
    let mut file_count = 0usize;
    for entry in WalkDir::new(dir).skip_hidden(false).into_iter().flatten() {
        if entry.file_type.is_file() {
            file_count += 1;
            total_bytes += entry.metadata().map(|m| m.len()).unwrap_or(0);
        }
    }
    (total_bytes, file_count)
}

fn is_path_excluded(path_str: &str, excluded_list: &[&str]) -> bool {
    for &ex in excluded_list {
        if path_str == ex {
            return true;
        }
        if let Some(rest) = path_str.strip_prefix(ex) {
            if rest.starts_with('/') {
                return true;
            }
        }
    }
    false
}

fn is_messenger_clutter(path_str: &str, parent_name: &str, ext: &str) -> bool {
    if path_str.contains("WhatsApp") || path_str.contains("com.whatsapp") {
        if parent_name == "sent" || parent_name == ".statuses" {
            return true;
        }
        if path_str.contains("WhatsApp Voice Notes") && ext == "opus" {
            return true;
        }
    }
    if path_str.contains("/Telegram/Telegram ")
        || path_str.contains("org.telegram.messenger")
    {
        return true;
    }
    false
}

fn classify_messenger_label(path_str: &str) -> &'static str {
    if path_str.contains(".Statuses") || path_str.contains(".statuses") {
        "WhatsApp Status Cache"
    } else if path_str.contains("/Sent/") {
        "WhatsApp Sent Media"
    } else if path_str.contains("WhatsApp Voice Notes") {
        "WhatsApp Voice Note"
    } else {
        "Telegram Media Cache"
    }
}

fn find_duplicates_parallel(
    size_buckets: HashMap<u64, Vec<(PathBuf, u64)>>,
) -> Vec<String> {
    let candidate_buckets: Vec<(u64, Vec<(PathBuf, u64)>)> = size_buckets
        .into_iter()
        .filter(|(_, files)| files.len() > 1)
        .collect();

    candidate_buckets
        .into_par_iter()
        .flat_map(|(file_size, files)| {
            let mut results = Vec::new();

            let mut partial_groups: HashMap<blake3::Hash, Vec<(PathBuf, u64)>> = HashMap::new();
            for (path, mtime) in files {
                if let Some(partial_hash) = hash_file_header(&path) {
                    partial_groups
                        .entry(partial_hash)
                        .or_default()
                        .push((path, mtime));
                }
            }

            for (_, header_matches) in partial_groups.into_iter().filter(|(_, v)| v.len() > 1) {
                let mut full_groups: HashMap<blake3::Hash, Vec<(PathBuf, u64)>> = HashMap::new();
                for (path, mtime) in header_matches {
                    if let Some(full_hash) = hash_file_full(&path) {
                        full_groups
                            .entry(full_hash)
                            .or_default()
                            .push((path, mtime));
                    }
                }

                for (full_hash, mut exact_dups) in
                    full_groups.into_iter().filter(|(_, v)| v.len() > 1)
                {
                    exact_dups.sort_by_key(|(_, mtime)| *mtime);

                    let short_hash = &full_hash.to_hex()[..8];
                    let original_name = exact_dups[0]
                        .0
                        .file_name()
                        .and_then(|n| n.to_str())
                        .unwrap_or("original");

                    for (dup_path, _) in exact_dups.iter().skip(1) {
                        if let Some(dup_str) = dup_path.to_str() {
                            results.push(format!(
                                "7|{}|Dup of {} [{}]|{}\n",
                                file_size, original_name, short_hash, dup_str
                            ));
                        }
                    }
                }
            }

            results
        })
        .collect()
}

fn hash_file_header(path: &Path) -> Option<blake3::Hash> {
    let mut file = File::open(path).ok()?;
    let mut buffer = [0u8; PARTIAL_HASH_BYTES];
    let bytes_read = file.read(&mut buffer).ok()?;
    if bytes_read == 0 {
        return None;
    }
    Some(blake3::hash(&buffer[..bytes_read]))
}

fn hash_file_full(path: &Path) -> Option<blake3::Hash> {
    let mut file = File::open(path).ok()?;
    let _ = file.seek(SeekFrom::Start(0));
    let mut hasher = blake3::Hasher::new();
    let mut buffer = [0u8; 65_536];
    loop {
        let bytes_read = file.read(&mut buffer).ok()?;
        if bytes_read == 0 {
            break;
        }
        hasher.update(&buffer[..bytes_read]);
    }
    Some(hasher.finalize())
}