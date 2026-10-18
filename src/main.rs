mod client;
mod scenario;

use std::{
    fs,
    path::PathBuf,
    time::{Duration, Instant},
};

use clap::Parser;
use rand::Rng;
use scenario::{ArrivalModel, Scenario, WorkloadTask};
use uuid::Uuid;

use crate::client::NodeClient;

#[derive(Debug, Parser)]
struct Args {
    #[arg(long)]
    scenario: PathBuf,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt::init();

    let args = Args::parse();
    let scenario_file = fs::read_to_string(&args.scenario)?;
    let scenario: Scenario = serde_yaml::from_str(&scenario_file)?;

    if scenario.target_rps == 0 {
        return Err("target_rps must be greater than zero".into());
    }

    match scenario.arrival_model {
        ArrivalModel::Uniform => {}
        ArrivalModel::Poisson | ArrivalModel::Bursts => {
            tracing::warn!(
                "Poisson and Bursts arrival models are not yet implemented; using Uniform"
            );
        }
        ArrivalModel::Waves => {
            tracing::warn!("Waves arrival model is not yet implemented; using Uniform");
        }
    }

    let client = NodeClient::new(String::new());
    let request_interval = Duration::from_secs_f64(1.0 / scenario.target_rps as f64);
    let end_time = Instant::now() + Duration::from_secs(scenario.duration_secs);
    let mut rng = rand::rng();

    while Instant::now() < end_time {
        let circuit_type = choose_circuit_type(&scenario, &mut rng)
            .ok_or("task_distribution must contain a positive percentage")?;
        let task = WorkloadTask {
            task_id: Uuid::new_v4(),
            proof_system: circuit_type.clone(),
            circuit_type,
            circuit_size: rng.random_range(1_000..=10_000),
            fee: rng.random_range(1..=1_000),
            client_id: "load-generator-1".to_string(),
        };

        client.submit_task(&task).await?;
        tracing::info!(task_id = %task.task_id, circuit_type = %task.circuit_type, "Submitted workload task");

        tokio::time::sleep(request_interval).await;
    }

    Ok(())
}

fn choose_circuit_type<R: Rng + ?Sized>(
    scenario: &Scenario,
    rng: &mut R,
) -> Option<String> {
    let total_percentage: f64 = scenario
        .task_distribution
        .values()
        .filter(|percentage| **percentage > 0.0)
        .sum();
    if total_percentage <= 0.0 {
        return None;
    }

    let threshold = rng.random_range(0.0..total_percentage);
    let mut cumulative = 0.0;

    for (circuit_type, percentage) in &scenario.task_distribution {
        if *percentage > 0.0 {
            cumulative += percentage;
            if threshold < cumulative {
                return Some(circuit_type.clone());
            }
        }
    }

    None
}
