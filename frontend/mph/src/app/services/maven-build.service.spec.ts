import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE_URL } from '../api-base-url';
import { BuildOptions, BuildStatus, MavenBuildService, ProjectProgress } from './maven-build.service';

class TestEventSource {
  static instances: TestEventSource[] = [];
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;

  constructor(readonly url: string) {
    TestEventSource.instances.push(this);
  }

  close(): void { this.closed = true; }
}

describe('MavenBuildService', () => {
  let service: MavenBuildService;
  let http: HttpTestingController;
  const originalEventSource = globalThis.EventSource;

  beforeEach(() => {
    TestEventSource.instances = [];
    globalThis.EventSource = TestEventSource as unknown as typeof EventSource;
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE_URL, useValue: '/backend' }]
    });
    service = TestBed.inject(MavenBuildService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    globalThis.EventSource = originalEventSource;
  });

  it('starts a build with paths and options', () => {
    const options: BuildOptions = { skipUTs: false, skipITs: true, parallel: true, maxParallel: 3 };
    service.startBuild(['/a/pom.xml'], options).subscribe();

    const request = http.expectOne('/backend/api/builds/start');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ projectPaths: ['/a/pom.xml'], options });
    request.flush(null);
  });

  it('stops a build and loads project logs', () => {
    service.stopBuild().subscribe();
    const stop = http.expectOne('/backend/api/builds/stop');
    expect(stop.request.body).toEqual({});
    stop.flush(null);

    let logs: string[] | undefined;
    service.getLogs('/a/pom.xml').subscribe(value => logs = value);
    const logRequest = http.expectOne(req => req.url === '/backend/api/builds/logs');
    expect(logRequest.request.params.get('projectPath')).toBe('/a/pom.xml');
    logRequest.flush(['line one', 'line two']);
    expect(logs).toEqual(['line one', 'line two']);
  });

  it('maps server sent events and closes the source when unsubscribed', () => {
    const received: ProjectProgress[] = [];
    const subscription = service.getBuildEvents().subscribe(value => received.push(value));
    const source = TestEventSource.instances[0];
    const event = { projectPath: '/a/pom.xml', artifactId: 'a', status: BuildStatus.RUNNING, logLine: 'building' };

    source.onmessage?.(new MessageEvent('message', { data: JSON.stringify(event) }));

    expect(source.url).toBe('/backend/api/builds/events');
    expect(received).toEqual([event]);
    subscription.unsubscribe();
    expect(source.closed).toBe(true);
  });
});
