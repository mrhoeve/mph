import { Component, EventEmitter, Output, signal, inject, Input, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProjectAnalysis } from '../../../services/maven-project-service';

@Component({
  selector: 'app-update-modules-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './update-modules-modal.component.html',
  styles: [`
    .radio-group {
      margin: 1.5rem 0;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    .radio-option {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      cursor: pointer;
      font-weight: 500;
      color: #374151;
    }
    .radio-option input[type="radio"] {
      width: 1.1rem;
      height: 1.1rem;
      cursor: pointer;
    }
    .version-input-container {
      margin-left: 1.85rem;
      margin-top: 0.5rem;
    }
    .version-input {
      width: 100%;
      padding: 0.5rem 0.75rem;
      border: 1px solid #d1d5db;
      border-radius: 0.375rem;
      font-size: 0.875rem;
    }
    .version-input:focus {
      outline: none;
      border-color: #3b82f6;
      ring: 2px #bfdbfe;
    }
    .hint {
      margin-top: 0.4rem;
      font-size: 0.75rem;
      color: #6b7280;
    }
    .hint a {
      color: #3b82f6;
      text-decoration: none;
    }
    .hint a:hover {
      text-decoration: underline;
    }
    .animate-fade-in {
      animation: fadeIn 0.2s ease-out;
    }
    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(-5px); }
      to { opacity: 1; transform: translateY(0); }
    }
  `]
})
export class UpdateModulesModalComponent implements OnInit {
  @Input({ required: true }) project!: ProjectAnalysis;
  @Output() close = new EventEmitter<void>();
  @Output() execute = new EventEmitter<{path: string, version: string}>();

  readonly selectedOption = signal<'current' | 'specified'>('current');
  readonly specifiedVersion = signal('');

  ngOnInit() {
    this.specifiedVersion.set(this.project.latestTag || '');
  }

  onExecute(): void {
    const finalVersion = this.selectedOption() === 'current' 
      ? this.project.version 
      : this.specifiedVersion();
      
    this.execute.emit({
      path: this.project.path,
      version: finalVersion
    });
  }
}
