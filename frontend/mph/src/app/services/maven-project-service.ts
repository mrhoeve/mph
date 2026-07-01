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
  springBootVersion?: string;
  managedProperties: ManagedProperty[];
  error?: string;
  isRoot: boolean;
}

export interface ManagedProperty {
  name: string;
  value: string;
  inheritedValue: string | null;
  source: string;
  isOverridden: boolean;
  comment?: string;
}

export interface ProjectUsage {
  usedInGroupId: string;
  usedInArtifactId: string;
  usedVersion: string;
  path: string;
}


export interface SyncDevelopResponse {
  projects: ProjectAnalysis[];
  messages: string[];
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

  bulkUpdateVersion(rootProjectPaths: string[], prefix: string, updateDependents: boolean, mode: string = 'ADD_PREFIX', branchName: string | null = null): Observable<ProjectAnalysis[]> {
    return this.http.post<ProjectAnalysis[]>(`${this.apiBaseUrl}/api/projects/bulk-update-version`, {
      rootProjectPaths,
      prefix,
      updateDependents,
      mode,
      branchName
    });
  }


  upgradeSpringBoot(path: string, newVersion: string): Observable<ProjectAnalysis[]> {
    return this.http.post<ProjectAnalysis[]>(`${this.apiBaseUrl}/api/projects/upgrade-spring-boot`, {
      path,
      newVersion,
    });
  }

  overrideProperty(path: string, propertyName: string, newValue: string, remark: string | null): Observable<ProjectAnalysis[]> {
    return this.http.post<ProjectAnalysis[]>(`${this.apiBaseUrl}/api/projects/override-property`, {
      path,
      propertyName,
      newValue,
      remark,
    });
  }

  removePropertyOverride(path: string, propertyName: string): Observable<ProjectAnalysis[]> {
    return this.http.post<ProjectAnalysis[]>(`${this.apiBaseUrl}/api/projects/remove-property-override`, {
      path,
      propertyName,
    });
  }

  getManagedProperties(path: string): Observable<ManagedProperty[]> {
    return this.http.get<ManagedProperty[]>(`${this.apiBaseUrl}/api/projects/managed-properties`, {
      params: { path }
    });
  }

  getBuildOrder(): Observable<ProjectAnalysis[]> {
    return this.http.get<ProjectAnalysis[]>(`${this.apiBaseUrl}/api/projects/build-order`);
  }

  syncDevelop(rootProjectPaths: string[]): Observable<SyncDevelopResponse> {
    return this.http.post<SyncDevelopResponse>(`${this.apiBaseUrl}/api/projects/sync-develop`, {
      rootProjectPaths
    });
  }

  getExcelUrl(): string {
    return `${this.apiBaseUrl}/api/projects/export-excel`;
  }
}
