use crate::scenario::WorkloadTask;

pub struct NodeClient {
    pub base_url: String,
}

impl NodeClient {
    pub fn new(base_url: String) -> Self {
        Self { base_url }
    }

    pub async fn submit_task(&self, task: &WorkloadTask) -> Result<(), reqwest::Error> {
        if self.base_url.is_empty() {
            tracing::info!(?task, "Mocking task submission");
            return Ok(());
        }

        let url = format!("{}/api/v1/submit", self.base_url.trim_end_matches('/'));

        reqwest::Client::new()
            .post(url)
            .json(task)
            .send()
            .await?
            .error_for_status()?;

        Ok(())
    }
}
