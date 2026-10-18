use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Serialize, Deserialize, Debug, Clone)]
pub enum ArrivalModel {
    Uniform,
    Poisson,
    Bursts,
    Waves,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct Scenario {
    pub name: String,
    pub target_rps: u64,
    pub duration_secs: u64,
    pub arrival_model: ArrivalModel,
    pub task_distribution: HashMap<String, f64>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct WorkloadTask {
    pub task_id: Uuid,
    pub circuit_type: String,
    pub circuit_size: u64,
    pub proof_system: String,
    pub fee: u64,
    pub client_id: String,
}
