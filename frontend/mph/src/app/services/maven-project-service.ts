import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../api-base-url';

export interface ProjectAnalysis {
  groupId: string;
  artifactId: string;
  version: string;
  path: string;
  modules: ProjectAnalysis[];
  usages: ProjectUsage[];
  hasSpringBootParent: boolean;
}

export interface ProjectUsage {
  usedInGroupId: string;
  usedInArtifactId: string;
  usedVersion: string;
  path: string;
}

@Injectable({
  providedIn: 'root',
})
export class MavenProjectService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);

  analyze(): Observable<ProjectAnalysis[]> {
    return this.http.get<ProjectAnalysis[]>(`${this.apiBaseUrl}/api/projects/analyze`);
  }

  updateVersion(groupId: string, artifactId: string, newVersion: string): Observable<ProjectAnalysis[]> {
    return this.http.post<ProjectAnalysis[]>(`${this.apiBaseUrl}/api/projects/update-version`, {
      groupId,
      artifactId,
      newVersion,
    });
  }

  bulkUpdateVersion(rootProjectPaths: string[], prefix: string, updateDependents: boolean, mode: string = 'ADD_PREFIX'): Observable<ProjectAnalysis[]> {
    return this.http.post<ProjectAnalysis[]>(`${this.apiBaseUrl}/api/projects/bulk-update-version`, {
      rootProjectPaths,
      prefix,
      updateDependents,
      mode,
    });
  }
}
