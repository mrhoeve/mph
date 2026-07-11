import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SpringBootDiscoveryService } from './spring-boot-discovery.service';

describe('SpringBootDiscoveryService', () => {
  let service: SpringBootDiscoveryService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(SpringBootDiscoveryService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('maps GitHub tag names and removes the v prefix', () => {
    let versions: string[] | undefined;
    service.getVersionsFromInitializr().subscribe(value => versions = value);

    http.expectOne('https://api.github.com/repos/spring-projects/spring-boot/tags')
      .flush([{ name: 'v3.5.2' }, { name: '3.4.7' }]);

    expect(versions).toEqual(['3.5.2', '3.4.7']);
  });

  it('falls back to Initializr metadata after an endpoint error', () => {
    let versions: string[] | undefined;
    service.getVersionsFromInitializr().subscribe(value => versions = value);

    http.expectOne('https://api.github.com/repos/spring-projects/spring-boot/tags').flush('failed', { status: 503, statusText: 'Unavailable' });
    http.expectOne('https://start.spring.io/metadata/client').flush({
      bootVersions: { values: [{ id: '3.5.3' }, { name: '3.4.8' }, {}] }
    });

    expect(versions).toEqual(['3.5.3', '3.4.8']);
  });

  it('returns latest stable upgrades in current series and overall', () => {
    const suggestions = service.getSuggestions('3.4.1', [
      '3.4.0', '3.4.3', '3.5.0-RC1', '3.5.1', '4.0.0-M1', '3.4.2-SNAPSHOT'
    ]);

    expect(suggestions).toEqual({ currentVersion: '3.4.1', latestInSeries: '3.4.3', latestOverall: '3.5.1' });
  });

  it('returns no suggestions when current version is already latest', () => {
    expect(service.getSuggestions('3.5.1', ['3.4.3', '3.5.1'])).toEqual({
      currentVersion: '3.5.1', latestInSeries: null, latestOverall: null
    });
  });

  it('compares multi digit version segments numerically', () => {
    expect(service.getSuggestions('3.4.9', ['3.4.9', '3.4.10'])).toEqual({
      currentVersion: '3.4.9', latestInSeries: '3.4.10', latestOverall: null
    });
  });
});
