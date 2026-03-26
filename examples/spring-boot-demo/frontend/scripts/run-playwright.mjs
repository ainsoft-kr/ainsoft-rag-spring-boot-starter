import { spawn } from 'node:child_process';
import process from 'node:process';

const frontendDir = process.cwd();
const starterDir = new URL('../../../', `file://${frontendDir}/`).pathname;
const serverUrl = 'http://127.0.0.1:18080/';
const mavenRepoLocal = process.env.MAVEN_REPO_LOCAL || `${process.env.HOME}/.m2/repository`;

function spawnCommand(command, args, options = {}) {
  return spawn(command, args, {
    cwd: options.cwd,
    env: options.env,
    stdio: options.stdio ?? 'pipe',
    detached: options.detached ?? false
  });
}

function waitForServer(url, timeoutMillis) {
  const startedAt = Date.now();

  return new Promise((resolve, reject) => {
    const onServerExit = (code, signal) => {
      reject(new Error(`Demo server exited before becoming ready (code=${code ?? 'null'}, signal=${signal ?? 'null'})`));
    };

    server?.once('exit', onServerExit);

    const attempt = async () => {
      if (Date.now() - startedAt > timeoutMillis) {
        server?.off('exit', onServerExit);
        reject(new Error(`Timed out waiting for ${url}`));
        return;
      }

      try {
        const response = await fetch(url);
        if (response.ok) {
          server?.off('exit', onServerExit);
          resolve();
          return;
        }
      } catch {
        // The server is not ready yet.
      }

      setTimeout(attempt, 1000);
    };

    attempt();
  });
}

async function ensureServerUnavailable(url) {
  try {
    const response = await fetch(url);
    if (response.ok) {
      throw new Error(`Another process is already serving ${url}`);
    }
  } catch (error) {
    if (error instanceof Error && error.message.startsWith('Another process')) {
      throw error;
    }
    // No reachable server is present, which is the expected state.
  }
}

async function runBuild() {
  const build = spawnCommand('npm', ['run', 'build'], {
    cwd: frontendDir,
    env: process.env,
    stdio: 'inherit'
  });

  const exitCode = await new Promise((resolve) => {
    build.on('exit', (code) => resolve(code ?? 1));
  });

  if (exitCode !== 0) {
    throw new Error(`Frontend build failed with exit code ${exitCode}`);
  }
}

let server;

async function main() {
  try {
    await runBuild();
    await ensureServerUnavailable(serverUrl);

    server = spawnCommand(
      './gradlew',
      [
        `-Dmaven.repo.local=${mavenRepoLocal}`,
        ':spring-boot-demo:bootRun',
        "--args=--server.port=18080 --rag.indexPath=./examples/spring-boot-demo/build/e2e-rag-index",
        '-PskipFrontendBuild=true'
      ],
      {
        cwd: starterDir,
        env: {
          ...process.env,
          GRADLE_USER_HOME: process.env.GRADLE_USER_HOME || `${process.env.HOME}/.gradle`
        },
        detached: true
      }
    );

    server.stdout.on('data', (chunk) => {
      process.stdout.write(chunk);
    });

    server.stderr.on('data', (chunk) => {
      process.stderr.write(chunk);
    });

    await waitForServer(serverUrl, 180_000);

    const tests = spawnCommand(
      'npx',
      ['playwright', 'test'],
      {
        cwd: frontendDir,
        env: {
          ...process.env,
          PLAYWRIGHT_EXTERNAL_SERVER: '1'
        },
        stdio: 'inherit'
      }
    );

    const exitCode = await new Promise((resolve) => {
      tests.on('exit', (code) => resolve(code ?? 1));
    });

    process.exitCode = exitCode;
  } finally {
    try {
      if (server?.pid) {
        process.kill(-server.pid, 'SIGTERM');
      }
    } catch {
      // Ignore shutdown errors for already-exited processes.
    }
  }
}

main().catch((error) => {
  console.error(error);
  try {
    if (server?.pid) {
      process.kill(-server.pid, 'SIGTERM');
    }
  } catch {
    // Ignore shutdown errors for already-exited processes.
  }
  process.exitCode = 1;
});
