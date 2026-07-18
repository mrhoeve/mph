import { HttpClient } from '@angular/common/http';
import { inject, Injectable, NgZone } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../api-base-url';

export enum RebaseProgressStatus {
  PENDING = 'PENDING',
  RUNNING = 'RUNNING',
  SUCCESS = 'SUCCESS',
  CONFLICT = 'CONFLICT',
  SKIPPED = 'SKIPPED',
  FAILED = 'FAILED',
  ALIGNING = 'ALIGNING',
  COMPLETED = 'COMPLETED',
  PARTIAL = 'PARTIAL'
}

export interface RebaseRepositoryPlan {
  projectPath: string;
  artifactId: string;
  repositoryPath: string;
}

export interface RebaseStartResponse {
  prefix: string;
  repositories: RebaseRepositoryPlan[];
}

export interface RebaseProgress {
  projectPath: string | null;
  artifactId: string | null;
  repositoryPath: string | null;
  status: RebaseProgressStatus;
  message: string;
  recoveryHint: string | null;
  stashPreserved: boolean;
  overall: boolean;
  alignmentSkipped: boolean;
}

@Injectable({ providedIn: 'root' })
export class RebaseWorkflowService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);
  private readonly zone = inject(NgZone);

  start(rootProjectPaths: string[]): Observable<RebaseStartResponse> {
    return this.http.post<RebaseStartResponse>(`${this.apiBaseUrl}/api/rebase-develop/start`, {
      rootProjectPaths
    });
  }

  events(): Observable<RebaseProgress> {
    return new Observable<RebaseProgress>(observer => {
      const eventSource = new EventSource(`${this.apiBaseUrl}/api/rebase-develop/events`);
      eventSource.onmessage = event => this.zone.run(() => observer.next(JSON.parse(event.data)));
      eventSource.onerror = error => this.zone.run(() => observer.error(error));
      return () => eventSource.close();
    });
  }
}
