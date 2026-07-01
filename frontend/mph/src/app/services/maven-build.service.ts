import { HttpClient } from '@angular/common/http';
import { inject, Injectable, NgZone } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../api-base-url';

export enum BuildStatus {
  PENDING = 'PENDING',
  RUNNING = 'RUNNING',
  SUCCESS = 'SUCCESS',
  FAILED = 'FAILED',
  SKIPPED = 'SKIPPED'
}

export interface BuildOptions {
  skipUTs: boolean;
  skipITs: boolean;
  parallel: boolean;
}

export interface ProjectProgress {
  projectPath: string;
  artifactId: string;
  status: BuildStatus;
  logLine: string | null;
}

@Injectable({
  providedIn: 'root',
})
export class MavenBuildService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);
  private readonly zone = inject(NgZone);

  startBuild(projectPaths: string[], options: BuildOptions): Observable<void> {
    return this.http.post<void>(`${this.apiBaseUrl}/api/builds/start`, {
      projectPaths,
      options,
    });
  }

  stopBuild(): Observable<void> {
    return this.http.post<void>(`${this.apiBaseUrl}/api/builds/stop`, {});
  }

  getLogs(projectPath: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiBaseUrl}/api/builds/logs`, {
      params: { projectPath }
    });
  }

  getBuildEvents(): Observable<ProjectProgress> {
    return new Observable<ProjectProgress>(observer => {
      const eventSource = new EventSource(`${this.apiBaseUrl}/api/builds/events`);

      eventSource.onmessage = event => {
        this.zone.run(() => {
          observer.next(JSON.parse(event.data));
        });
      };

      eventSource.onerror = error => {
        this.zone.run(() => {
          observer.error(error);
        });
      };

      return () => {
        eventSource.close();
      };
    });
  }
}
