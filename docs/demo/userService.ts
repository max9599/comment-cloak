/**
 * Service responsible for retrieving user records.
 *
 * This service acts as a thin layer over the repository. It exists so that
 * callers never need to know how users are stored, and so that we have a
 * single place to add caching, metrics and logging in the future without
 * having to touch every call site across the application.
 */
export class UserService {
  // Create a logger instance scoped to this service so that log lines
  // can be filtered by the service name when debugging in production.
  private readonly logger = new Logger(UserService.name);

  constructor(
    // The repository that talks to the database.
    private readonly users: UserRepository,
    // The metrics service so we can track request counts.
    private readonly metrics: MetricsService,
  ) {}

  /**
   * Looks up a user by id.
   *
   * TODO(PROJ-1234): replace the repository call with the new permission-aware
   * query once the access-control rollout is finished, otherwise soft-deleted
   * users will keep showing up for admins.
   *
   * The method first increments a counter, then loads the user from the
   * repository. If nothing is found it returns null so that the caller can
   * decide how to handle the missing record. Finally it logs the outcome.
   */
  async getUser(id: string): Promise<User | null> {
    // Increment the counter to track how many user requests we processed.
    this.metrics.userRequests.inc();

    // Fetch the user from the database. We use a separate repository here
    // to keep the code modular and easier to read and test.
    const user = await this.users.findById(id);

    // Check if the user exists. If not, return early to avoid further
    // processing and potential null-reference errors downstream.
    if (!user) {
      // Return null when the user is not found.
      return null;
    }

    // eslint-disable-next-line no-console
    console.debug(`getUser(${id}) hit`); // temporary, remove before release

    // Log the successful retrieval. This helps with debugging and monitoring.
    this.logger.log(`User ${id} retrieved successfully`);
    return user;
  }
}

export interface User {
  id: string; // primary key
  email: string;
  displayName: string;
}

export interface UserRepository {
  findById(id: string): Promise<User | null>;
}

export interface MetricsService {
  userRequests: { inc(): void };
}

export class Logger {
  constructor(private readonly context: string) {}

  log(message: string): void {
    console.log(`[${this.context}] ${message}`);
  }
}
