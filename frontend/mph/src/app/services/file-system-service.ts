import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../api-base-url';

export interface FolderResponse {
  path: string;
  parentPath: string | null;
  remembered: boolean;
  maxScanDepth: number;
  nexusIqUrl?: string;
  nexusIqUser?: string;
  nexusIqPass?: string;
  nexusIqAppIdPrefix?: string;
  nexusIqAppIdSuffix?: string;
  children: FolderItem[];
}

export interface FolderItem {
  name: string;
  path: string;
}

@Injectable({
  providedIn: 'root',
})
export class FileSystemService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);

  current(): Observable<FolderResponse> {
    return this.http.get<FolderResponse>(`${this.apiBaseUrl}/api/filesystem/current`);
  }

  folders(path: string): Observable<FolderResponse> {
    const params = new HttpParams().set('path', path);

    return this.http.get<FolderResponse>(`${this.apiBaseUrl}/api/filesystem/folders`, {
      params,
    });
  }

  saveBase(path: string, maxScanDepth: number, nexusIqUrl?: string, nexusIqUser?: string, nexusIqPass?: string, nexusIqAppIdPrefix?: string, nexusIqAppIdSuffix?: string): Observable<FolderResponse> {
    return this.http.post<FolderResponse>(`${this.apiBaseUrl}/api/filesystem/base`, {
      path,
      maxScanDepth,
      nexusIqUrl,
      nexusIqUser,
      nexusIqPass,
      nexusIqAppIdPrefix,
      nexusIqAppIdSuffix
    });
  }
}
