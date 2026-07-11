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
  canManageComponentVersions: boolean;
  springBootVersion?: string;
  managedProperties: ManagedProperty[];
  latestTag?: string;
  latestTagInfo?: TagInfo;
  gitStatus?: GitStatus;
  error?: string;
  isRoot: boolean;
  nexusIqResult?: NexusIqResult;
  canScanNexusIq: boolean;
  buildStep: number;
  dependsOn: string[];
}

export interface NexusIqResult {
  applicationPublicId: string;
  reportHtmlUrl?: string;
  policyViolations: NexusIqPolicyViolation[];
  message?: string;
}

export interface NexusIqPolicyViolation {
  componentIdentifier: string;
  threatLevel: number;
  policyName: string;
  constraintViolations: string[];
  remediationVersion?: string;
}

export interface ManagedProperty {
  name: string;
  value: string;
  inheritedValue: string | null;
  source: string;
  isOverridden: boolean;
  comment?: string;
  nexusIqViolations?: NexusIqPolicyViolation[];
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


export interface TagInfo {
  version: string;
  tagName: string;
}

export interface GitStatus {
  branchName: string;
  aheadCount: number;
  behindCount: number;
}

export interface SbomComponent {
  groupId: string;
  artifactId: string;
  version: string;
  scope: string | null;
  type: string | null;
  description?: string;
  licenses: string[];
  dependencies: SbomComponent[];
}

export interface SbomDetails {
  components: SbomComponent[];
  rawXml: string;
  rawJson: string;
}

export interface NexusIqScanResponse {
  message: string;
  reportUrl?: string;
  summary?: NexusIqScanSummary;
  violations: NexusIqReportViolation[];
}

export interface NexusIqScanSummary {
  critical: number;
  severe: number;
  moderate: number;
  low: number;
  total: number;
  affectedComponents: number;
}

export interface NexusIqReportViolation {
  componentIdentifier?: string;
  packageUrl?: string;
  policyName: string;
  threatLevel: number;
  reasons: string[];
  directDependency: boolean;
  waived: boolean;
  details: NexusIqReportViolationDetail[];
}

export interface NexusIqReportViolationDetail {
  policyName: string;
  threatLevel: number;
  reasons: string[];
  waived: boolean;
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

  bulkUpdateVersion(rootProjectPaths: string[], prefix: string, updateDependents: boolean, mode: string = 'ADD_PREFIX', branchName: string | null = null, updateProjects: boolean = true): Observable<ProjectAnalysis[]> {
    return this.http.post<ProjectAnalysis[]>(`${this.apiBaseUrl}/api/projects/bulk-update-version`, {
      rootProjectPaths,
      prefix,
      updateDependents,
      mode,
      branchName,
      updateProjects
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

  getLatestTag(path: string): Observable<TagInfo | null> {
    return this.http.get<TagInfo | null>(`${this.apiBaseUrl}/api/projects/latest-tag`, {
      params: { path }
    });
  }

  getBuildOrder(): Observable<ProjectAnalysis[]> {
    return this.http.get<ProjectAnalysis[]>(`${this.apiBaseUrl}/api/projects/build-order`);
  }

  syncDevelop(rootProjectPaths: string[], mergeDevelop: boolean = false): Observable<SyncDevelopResponse> {
    return this.http.post<SyncDevelopResponse>(`${this.apiBaseUrl}/api/projects/sync-develop`, {
      rootProjectPaths,
      mergeDevelop
    });
  }

  getExcelUrl(): string {
    return `${this.apiBaseUrl}/api/projects/export-excel`;
  }

  getSbomDetails(path: string): Observable<SbomDetails> {
    return this.http.get<SbomDetails>(`${this.apiBaseUrl}/api/projects/sbom/details`, {
      params: { path }
    });
  }

  getSbomExportUrl(path: string, format: string): string {
    return `${this.apiBaseUrl}/api/projects/sbom/export?path=${encodeURIComponent(path)}&format=${format}`;
  }

  scanNexusIq(path: string): Observable<NexusIqScanResponse> {
    return this.http.post<NexusIqScanResponse>(`${this.apiBaseUrl}/api/nexus-iq/scan`, { path });
  }
}
