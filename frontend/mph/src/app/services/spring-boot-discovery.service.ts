import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable, catchError, of } from 'rxjs';

export interface SpringBootUpgradeSuggestions {
  currentVersion: string;
  latestInSeries: string | null;
  latestOverall: string | null;
}

@Injectable({
  providedIn: 'root',
})
export class SpringBootDiscoveryService {
  private readonly http = inject(HttpClient);

  getVersionsFromInitializr(): Observable<string[]> {
    const endpoints = [
      'https://api.github.com/repos/spring-projects/spring-boot/tags',
      'https://start.spring.io/metadata/client',
      'https://start.spring.io/metadata/config',
      'https://start.spring.io/'
    ];

    return this.tryEndpoints(endpoints);
  }

  private tryEndpoints(urls: string[]): Observable<string[]> {
    if (urls.length === 0) {
      return of([]);
    }

    const url = urls[0];
    return this.http.get<any>(url, {
      headers: { 'Accept': 'application/json, application/vnd.initializr.v2.2+json' }
    }).pipe(
      map(data => {
        if (!data) return [];
        
        // Handle GitHub tags/releases format: [{name: "v4.1.0"}, ...]
        if (Array.isArray(data) && data.length > 0 && data[0].name) {
          return data.map((item: any) => {
            const name = item.name;
            return name.startsWith('v') ? name.substring(1) : name;
          });
        }

        // Handle if it's already an array of strings
        if (Array.isArray(data) && data.length > 0 && typeof data[0] === 'string') {
          return data;
        }

        const bv = data.bootVersions || data.bootVersion || data['boot-versions'] || data['boot-version'];
        if (bv && Array.isArray(bv.values)) {
          return bv.values.map((v: any) => v.id || v.name).filter((v: any) => !!v);
        }

        // If it's a flat object with a values array
        if (Array.isArray(data.values)) {
          return data.values.map((v: any) => v.id || v.name).filter((v: any) => !!v);
        }

        return [];
      }),
      catchError(error => {
        console.error(`Error fetching Spring Boot versions from ${url}:`, error);
        if (urls.length > 1) {
          return this.tryEndpoints(urls.slice(1));
        }
        return of([]);
      })
    );
  }

  getSuggestions(currentVersion: string, allVersions: string[]): SpringBootUpgradeSuggestions {
    const stableVersions = allVersions
      .filter(v => this.isStable(v))
      .sort((a, b) => this.compareVersions(a, b));

    if (stableVersions.length === 0) {
      return { currentVersion, latestInSeries: null, latestOverall: null };
    }

    const latestOverall = stableVersions[stableVersions.length - 1];
    
    const parts = currentVersion.split('.');
    let latestInSeries: string | null = null;
    if (parts.length >= 2) {
      const series = `${parts[0]}.${parts[1]}.`;
      const seriesVersions = stableVersions.filter(v => v.startsWith(series));
      if (seriesVersions.length > 0) {
        latestInSeries = seriesVersions[seriesVersions.length - 1];
      }
    }

    return {
      currentVersion,
      latestInSeries: (latestInSeries && this.isNewer(latestInSeries, currentVersion)) ? latestInSeries : null,
      latestOverall: (this.isNewer(latestOverall, currentVersion) && latestOverall !== latestInSeries) ? latestOverall : null
    };
  }

  private isStable(version: string): boolean {
    const v = version.toLowerCase();
    return !v.includes('m') && !v.includes('rc') && !v.includes('snapshot') && !v.includes('milestone');
  }

  private isNewer(v1: string, v2: string): boolean {
    return this.compareVersions(v1, v2) > 0;
  }

  private compareVersions(v1: string, v2: string): number {
    const p1 = v1.split('.').map(s => parseInt(s, 10)).filter(n => !isNaN(n));
    const p2 = v2.split('.').map(s => parseInt(s, 10)).filter(n => !isNaN(n));
    const size = Math.max(p1.length, p2.length);
    for (let i = 0; i < size; i++) {
      const val1 = p1[i] || 0;
      const val2 = p2[i] || 0;
      if (val1 !== val2) return val1 - val2;
    }
    return 0;
  }
}
