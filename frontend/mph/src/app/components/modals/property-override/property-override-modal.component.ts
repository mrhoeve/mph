import { Component, EventEmitter, Output, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectAnalysis, ManagedProperty } from '../../../services/maven-project-service';

@Component({
  selector: 'app-property-override-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './property-override-modal.component.html'
})
export class PropertyOverrideModalComponent {
  project = input.required<ProjectAnalysis>();
  prop = input.required<ManagedProperty>();

  readonly overrideNewValue = signal('');
  readonly overrideRemark = signal('');

  @Output() close = new EventEmitter<void>();
  @Output() execute = new EventEmitter<{newValue: string, remark: string}>();

  constructor() {
    // We need to initialize signals from inputs if they are available
    // But since inputs are reactive, we might need an effect or ngOnInit
  }

  ngOnInit() {
    const remediation = this.prop().nexusIqViolations?.[0]?.remediationVersion;
    this.overrideNewValue.set(remediation || this.prop().value);
    this.overrideRemark.set(this.prop().comment || (remediation ? 'Nexus IQ Security Fix' : ''));
  }

  onExecute(): void {
    this.execute.emit({
      newValue: this.overrideNewValue(),
      remark: this.overrideRemark()
    });
  }
}
