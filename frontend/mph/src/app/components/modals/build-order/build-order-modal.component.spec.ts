import { ComponentFixture, TestBed } from '@angular/core/testing';
import mermaid from 'mermaid';
import { of, throwError } from 'rxjs';
import svgPanZoom from 'svg-pan-zoom';
import { vi } from 'vitest';
import { MavenProjectService, ProjectAnalysis } from '../../../services/maven-project-service';
import { BuildOrderModalComponent } from './build-order-modal.component';

vi.mock('mermaid', () => ({ default: { render: vi.fn() } }));
vi.mock('svg-pan-zoom', () => ({ default: vi.fn() }));

function project(artifactId: string, path: string, overrides: Partial<ProjectAnalysis> = {}): ProjectAnalysis {
  return { groupId: 'org.example', artifactId, version: '1.0', path, modules: [], usages: [], hasSpringBootParent: false,
    canManageComponentVersions: false, managedProperties: [], isRoot: false, canScanNexusIq: false, buildStep: 0, dependsOn: [], ...overrides };
}

describe('BuildOrderModalComponent', () => {
  let fixture: ComponentFixture<BuildOrderModalComponent>;
  const service = { getBuildOrder: vi.fn(), getExcelUrl: vi.fn() };

  beforeEach(async () => {
    vi.useFakeTimers();
    const childB = project('child-b', '/root/child-b');
    const childA = project('child-a', '/root/child-a');
    const root = project('root', '/root', { isRoot: true, modules: [childB, childA] });
    service.getBuildOrder.mockReset().mockReturnValue(of([root]));
    service.getExcelUrl.mockReset().mockReturnValue('/api/projects/build-order/excel');
    vi.mocked(mermaid.render).mockReset().mockResolvedValue({ svg: '<svg></svg>' } as any);
    vi.mocked(svgPanZoom).mockReset();
    await TestBed.configureTestingModule({
      imports: [BuildOrderModalComponent],
      providers: [{ provide: MavenProjectService, useValue: service }]
    }).overrideComponent(BuildOrderModalComponent, { set: { template: '' } }).compileComponents();
    fixture = TestBed.createComponent(BuildOrderModalComponent);
    fixture.detectChanges();
    vi.clearAllTimers();
  });

  afterEach(() => {
    document.getElementById('dependency-graph')?.remove();
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('loads roots and presents a sorted hierarchy with depth', () => {
    expect(fixture.componentInstance.isLoading()).toBe(false);
    expect(fixture.componentInstance.hierarchicalProjects().map(item => ({ id: item.project.artifactId, depth: item.depth, parent: item.isParent })))
      .toEqual([
        { id: 'root', depth: 0, parent: true },
        { id: 'child-a', depth: 1, parent: false },
        { id: 'child-b', depth: 1, parent: false }
      ]);
    expect(fixture.componentInstance.allProjectsFlattened().map(item => item.artifactId)).toEqual(['child-a', 'child-b', 'root']);
  });

  it('derives the focused name and indented display label', () => {
    fixture.componentInstance.selectedProjectPath.set('/root/child-a');
    expect(fixture.componentInstance.focusedProjectName()).toBe('child-a');
    expect(fixture.componentInstance.getProjectDisplayName({ project: project('module', '/module'), depth: 2 }))
      .toBe('\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0module');
  });

  it('opens the generated build-order spreadsheet', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    fixture.componentInstance.downloadExcel();
    expect(service.getExcelUrl).toHaveBeenCalledOnce();
    expect(open).toHaveBeenCalledWith('/api/projects/build-order/excel', '_blank');
  });

  it('encodes the rendered graph before exporting it as an image', () => {
    const graph = document.createElement('div');
    graph.id = 'dependency-graph';
    graph.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg"><text>Dependency → graph</text></svg>';
    document.body.appendChild(graph);
    const svg = graph.querySelector('svg') as SVGSVGElement;
    svg.getBBox = vi.fn().mockReturnValue({ x: 0, y: 0, width: 120, height: 80 });
    const encode = vi.spyOn(TextEncoder.prototype, 'encode');
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);

    fixture.componentInstance.saveAsImage();

    expect(encode).toHaveBeenCalledOnce();
    expect(encode.mock.calls[0][0]).toContain('Dependency → graph');
    graph.remove();
  });

  it('shows a stable error when build-order calculation fails', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    service.getBuildOrder.mockReturnValue(throwError(() => new Error('cycle')));
    fixture.componentInstance.loadBuildOrder();
    expect(fixture.componentInstance.isLoading()).toBe(false);
    expect(fixture.componentInstance.errorMessage()).toContain('Failed to calculate build order');
  });

  it('renders the complete graph and initializes navigation and tooltips', async () => {
    const component = fixture.componentInstance as any;
    const graph = document.createElement('div');
    graph.id = 'dependency-graph';
    document.body.appendChild(graph);
    const zoom = { zoomIn: vi.fn(), zoomOut: vi.fn(), reset: vi.fn(), center: vi.fn(), destroy: vi.fn() };
    vi.mocked(svgPanZoom).mockReturnValue(zoom as any);
    vi.mocked(mermaid.render).mockResolvedValue({
      svg: '<svg width="100px" height="80px" style="max-width: 100px;"><g class="node" id="org_example_root"></g></svg>'
    } as any);

    component.renderDependencyGraph();
    await Promise.resolve();
    await Promise.resolve();
    expect(graph.querySelector('svg')).not.toBeNull();

    const definition = vi.mocked(mermaid.render).mock.calls.at(-1)?.[1] ?? '';
    expect(definition).toContain('graph TD');
    expect(definition).toContain('child-a');
    expect(graph.querySelector('svg')?.getAttribute('width')).toBe('100%');
    expect(graph.querySelector('title')?.textContent).toContain('Artifact: root');
    expect(svgPanZoom).toHaveBeenCalledOnce();

    component.zoomIn();
    component.zoomOut();
    component.resetZoom();
    expect(zoom.zoomIn).toHaveBeenCalledOnce();
    expect(zoom.zoomOut).toHaveBeenCalledOnce();
    expect(zoom.reset).toHaveBeenCalledOnce();
    expect(zoom.center).toHaveBeenCalledOnce();
    graph.remove();
  });

  it('focuses a module together with its hierarchy and direct relationships', async () => {
    const component = fixture.componentInstance as any;
    const consumer = project('consumer', '/consumer', {
      isRoot: true,
      usages: [{ usedInGroupId: 'org.example', usedInArtifactId: 'child', usedVersion: '1.0', path: '/root/child' }]
    });
    const child = project('child', '/root/child', {
      usages: [{ usedInGroupId: 'org.example', usedInArtifactId: 'consumer', usedVersion: '1.0', path: '/consumer' }]
    });
    const root = project('root', '/root', { isRoot: true, modules: [child] });
    component.buildOrderProjects.set([root, consumer]);
    component.selectedProjectPath.set('/root/child');
    const graph = document.createElement('div');
    graph.id = 'dependency-graph';
    document.body.appendChild(graph);
    vi.mocked(mermaid.render).mockResolvedValue({ svg: '<svg></svg>' } as any);

    component.renderDependencyGraph();
    await Promise.resolve();
    await Promise.resolve();
    expect(mermaid.render).toHaveBeenCalled();

    const definition = vi.mocked(mermaid.render).mock.calls.at(-1)?.[1] ?? '';
    expect(definition).toContain('title: Focus on child');
    expect(definition).toContain('focusedNode');
    expect(definition).toContain('module');
    expect(definition).toContain('-->');
    graph.remove();
  });

  it('covers hierarchy filtering and identifier helpers', () => {
    const component = fixture.componentInstance as any;
    const leaf = project('leaf.name', '/root/leaf');
    const emptyParent = project('empty-parent', '/empty', { isRoot: true, modules: [project('hidden', '/empty/hidden')] });
    const root = project('root', '/root', { isRoot: true, modules: [leaf] });
    const flattened = [root, leaf, emptyParent, ...emptyParent.modules];
    const included = new Set(flattened.map(item => item.path));

    expect(component.sanitizeId('org.example:leaf.name')).toBe('org_example_leaf_name');
    expect(component.flatten(root)).toEqual([root, leaf]);
    expect(component.findRootForUsage([root, emptyParent], leaf.path)).toBe(root);
    expect(component.findRootForUsage([root], '/missing')).toBeNull();
    expect(component.isUsefulProject(leaf, flattened, included)).toBe(true);
    expect(component.isUsefulParent(emptyParent, flattened, included)).toBe(false);
    expect(component.renderProjectNode(emptyParent, 1, included, flattened)).toContain('hidden');
    expect(component.renderProjectNode(root, 1, new Set(), flattened)).toBe('');

    component.selectedProjectPath.set(root.path);
    expect(component.isUsefulParent(root, flattened, included)).toBe(true);
    expect(component.renderProjectNode(root, 1, included, flattened)).toContain('focusedNode');
  });

  it('updates graph focus and safely handles absent export and zoom elements', () => {
    const component = fixture.componentInstance as any;
    const render = vi.spyOn(component, 'renderDependencyGraph').mockImplementation(() => undefined);
    document.getElementById('dependency-graph')?.remove();

    component.onProjectFocusChange({ target: { value: '/root/child-a' } });
    component.saveAsImage();
    component.initializeZoom();
    component.zoomIn();
    component.zoomOut();
    component.resetZoom();

    expect(component.selectedProjectPath()).toBe('/root/child-a');
    expect(render).toHaveBeenCalledOnce();
    expect(component.getFormattedTimestamp()).toMatch(/^\d{12}$/);
  });
});
