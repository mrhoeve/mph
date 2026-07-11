import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE_URL } from '../api-base-url';
import { MavenProjectService } from './maven-project-service';

describe('MavenProjectService', () => {
  let service: MavenProjectService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE_URL, useValue: '/backend' }]
    });
    service = TestBed.inject(MavenProjectService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('analyzes projects and loads build order', () => {
    service.analyze().subscribe(value => expect(value).toEqual([]));
    const analyze = http.expectOne('/backend/api/projects/analyze');
    expect(analyze.request.method).toBe('GET');
    analyze.flush([]);

    service.getBuildOrder().subscribe(value => expect(value).toEqual([]));
    const order = http.expectOne('/backend/api/projects/build-order');
    expect(order.request.method).toBe('GET');
    order.flush([]);
  });

  it('updates a project version with exact coordinates', () => {
    service.updateVersion('org.example', 'sample', '2.0.0').subscribe();

    const request = http.expectOne('/backend/api/projects/update-version');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ groupId: 'org.example', artifactId: 'sample', newVersion: '2.0.0' });
    request.flush([]);
  });

  it('submits every bulk update option', () => {
    service.bulkUpdateVersion(['/a/pom.xml'], 'feature-', true, 'ADD_PREFIX', 'feature/test', false).subscribe();

    const request = http.expectOne('/backend/api/projects/bulk-update-version');
    expect(request.request.body).toEqual({
      rootProjectPaths: ['/a/pom.xml'], prefix: 'feature-', updateDependents: true,
      mode: 'ADD_PREFIX', branchName: 'feature/test', updateProjects: false
    });
    request.flush([]);
  });

  it('upgrades Spring Boot and manages property overrides', () => {
    service.upgradeSpringBoot('/a/pom.xml', '4.1.1').subscribe();
    const upgrade = http.expectOne('/backend/api/projects/upgrade-spring-boot');
    expect(upgrade.request.body).toEqual({ path: '/a/pom.xml', newVersion: '4.1.1' });
    upgrade.flush([]);

    service.overrideProperty('/a/pom.xml', 'library.version', '2.0.0', 'security update').subscribe();
    const override = http.expectOne('/backend/api/projects/override-property');
    expect(override.request.body).toEqual({
      path: '/a/pom.xml', propertyName: 'library.version', newValue: '2.0.0', remark: 'security update'
    });
    override.flush([]);

    service.removePropertyOverride('/a/pom.xml', 'library.version').subscribe();
    const remove = http.expectOne('/backend/api/projects/remove-property-override');
    expect(remove.request.body).toEqual({ path: '/a/pom.xml', propertyName: 'library.version' });
    remove.flush([]);
  });

  it('loads managed properties and latest tag by path', () => {
    service.getManagedProperties('/a/pom.xml').subscribe(value => expect(value).toEqual([]));
    const properties = http.expectOne(req => req.url === '/backend/api/projects/managed-properties');
    expect(properties.request.params.get('path')).toBe('/a/pom.xml');
    properties.flush([]);

    service.getLatestTag('/a/pom.xml').subscribe(value => expect(value).toEqual({ version: '1.2.3', tagName: 'v1.2.3' }));
    const tag = http.expectOne(req => req.url === '/backend/api/projects/latest-tag');
    expect(tag.request.params.get('path')).toBe('/a/pom.xml');
    tag.flush({ version: '1.2.3', tagName: 'v1.2.3' });
  });

  it('syncs develop with merge choice', () => {
    service.syncDevelop(['/a/pom.xml'], true).subscribe(value => expect(value.messages).toEqual(['merged']));

    const request = http.expectOne('/backend/api/projects/sync-develop');
    expect(request.request.body).toEqual({ rootProjectPaths: ['/a/pom.xml'], mergeDevelop: true });
    request.flush({ projects: [], messages: ['merged'] });
  });

  it('builds encoded export URLs', () => {
    expect(service.getExcelUrl()).toBe('/backend/api/projects/export-excel');
    expect(service.getSbomExportUrl('/projects/a module/pom.xml', 'json')).toBe(
      '/backend/api/projects/sbom/export?path=%2Fprojects%2Fa%20module%2Fpom.xml&format=json'
    );
  });

  it('loads SBOM details and triggers Nexus IQ scan', () => {
    service.getSbomDetails('/a/pom.xml').subscribe(value => expect(value.rawJson).toBe('{}'));
    const sbom = http.expectOne(req => req.url === '/backend/api/projects/sbom/details');
    expect(sbom.request.params.get('path')).toBe('/a/pom.xml');
    sbom.flush({ components: [], rawXml: '<bom/>', rawJson: '{}' });

    service.scanNexusIq('/a/pom.xml').subscribe(value => expect(value.message).toBe('completed'));
    const scan = http.expectOne('/backend/api/nexus-iq/scan');
    expect(scan.request.body).toEqual({ path: '/a/pom.xml' });
    scan.flush({ message: 'completed', violations: [] });
  });
});
