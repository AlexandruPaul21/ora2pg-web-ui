import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Project, ProjectService } from '../../services/project.service';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { PasswordModule } from 'primeng/password';
import { ToastModule } from 'primeng/toast';
import { DividerModule } from 'primeng/divider';
import { ConfirmationService, MessageService } from 'primeng/api';
import { Router, RouterLink } from '@angular/router';
import {ConfirmDialog} from 'primeng/confirmdialog';
import {Tooltip} from 'primeng/tooltip';
import {Textarea} from 'primeng/textarea';
import {FieldsetModule} from 'primeng/fieldset';
import {Select} from 'primeng/select';
import {InputGroup} from 'primeng/inputgroup';
import {DomSanitizer, SafeHtml} from '@angular/platform-browser';
import {Tag} from 'primeng/tag';
import {MultiSelect} from 'primeng/multiselect';
import {SelectButton} from 'primeng/selectbutton';
import {CheckboxModule} from 'primeng/checkbox';
import {ContextMenu, ContextMenuModule} from 'primeng/contextmenu';
import {MenuItem} from 'primeng/api';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    TableModule,
    ButtonModule,
    DialogModule,
    InputTextModule,
    PasswordModule,
    ToastModule,
    DividerModule,
    RouterLink,
    ConfirmDialog,
    Tooltip,
    FieldsetModule,
    Textarea,
    Select,
    InputGroup,
    Tag,
    MultiSelect,
    SelectButton,
    CheckboxModule,
    ContextMenuModule,
  ],
  providers: [MessageService, ConfirmationService],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss'
})
export class DashboardComponent implements OnInit {
  projectService = inject(ProjectService);
  messageService = inject(MessageService);
  confirmationService = inject(ConfirmationService);
  sanitizer = inject(DomSanitizer);
  router = inject(Router);

  oracleConnTypes = [
    { label: 'SID', value: 'SID' },
    { label: 'Service Name', value: 'SERVICE_NAME' },
    { label: 'Custom DSN', value: 'CUSTOM' }
  ];

  postgresSslModes = [
    { label: 'Disable', value: 'disable' },
    { label: 'Require', value: 'require' },
    { label: 'Verify-CA', value: 'verify-ca' },
    { label: 'Verify-Full', value: 'verify-full' }
  ];

  projects: Project[] = [];
  displayDialog: boolean = false;
  isEditing: boolean = false;
  displayReportDialog: boolean = false;
  isLoadingReport: boolean = false;
  reportHtml: SafeHtml = '';
  displayHistoryDialog: boolean = false;
  displayLogDialog: boolean = false;
  historyRuns: any[] = [];
  selectedLogs: string = '';

  availableTables: {label: string, value: string}[] = [];
  selectedTablesList: string[] = [];
  isFetchingTables = false;
  tableFilterModes = [
    {label: 'Allow (whitelist)', value: 'ALLOW'},
    {label: 'Exclude (blacklist)', value: 'EXCLUDE'}
  ];

  // Context menu
  contextMenuItems: MenuItem[] = [];
  selectedProject: Project | null = null;

  // Validation
  displayValidationDialog = false;
  displayValidationHistoryDialog = false;
  displayValidationReportDialog = false;
  displayValidationLogDialog = false;
  validationProject: Project | null = null;
  validationHistoryRuns: any[] = [];
  validationReport: any = null;
  selectedValidationLogs = '';

  schemaValidationOptions = [
    { key: 'TABLES', label: 'Tables & Columns (structure + types)', selected: true },
    { key: 'PKS', label: 'Primary Keys', selected: true },
    { key: 'FKS', label: 'Foreign Keys', selected: true },
    { key: 'INDEXES', label: 'Indexes', selected: true },
    { key: 'SEQUENCES', label: 'Sequences', selected: true },
    { key: 'CONSTRAINTS', label: 'Check Constraints', selected: true },
    { key: 'DEFAULTS', label: 'Column Defaults', selected: false }
  ];

  dataValidationOptions = [
    { key: 'ROW_COUNTS', label: 'Row Counts (per table)', selected: true },
    { key: 'CHECKSUMS', label: 'Column Checksums (SUM/hash comparison)', selected: false }
  ];

  newProject: Project = this.getEmptyProject();

  ngOnInit() {
    this.loadProjects();
    this.contextMenuItems = [
      {
        label: 'Migration History',
        icon: 'pi pi-history',
        command: () => this.viewHistory(this.selectedProject!)
      },
      {
        label: 'Validation History',
        icon: 'pi pi-verified',
        command: () => this.viewValidationHistory(this.selectedProject!)
      },
      { separator: true },
      {
        label: 'Edit',
        icon: 'pi pi-pencil',
        command: () => this.editProject(this.selectedProject!)
      },
      {
        label: 'Delete',
        icon: 'pi pi-trash',
        command: () => this.deleteProject(this.selectedProject!)
      }
    ];
  }

  loadProjects() {
    this.projectService.getProjects().subscribe(data => {
      this.projects = data;
    });
  }

  viewHistory(project: Project) {
    if (!project.id) return;
    this.projectService.getMigrationHistory(project.id).subscribe(runs => {
      this.historyRuns = runs;
      this.displayHistoryDialog = true;
    });
  }

  viewLogs(runId: number) {
    this.projectService.getMigrationLogs(runId).subscribe(logs => {
      this.selectedLogs = logs;
      this.displayLogDialog = true;
    });
  }

  getStatusSeverity(status: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast' | undefined {
    switch (status) {
      case 'SUCCESS': return 'success';
      case 'SUCCESS_WITH_WARNING': return 'warn';
      case 'FAILED': return 'danger';
      case 'RUNNING': return 'info';
      default: return 'secondary';
    }
  }

  viewAssessmentReport(project: Project) {
    if (!project.id) return;

    this.displayReportDialog = true;
    this.isLoadingReport = true;
    this.reportHtml = ''; // Clear old report

    this.projectService.getAssessmentReport(project.id).subscribe({
      next: (html) => {
        // Tell Angular this HTML is safe to inject into an iframe
        this.reportHtml = this.sanitizer.bypassSecurityTrustHtml(html);
        this.isLoadingReport = false;
      },
      error: (err) => {
        this.isLoadingReport = false;
        this.messageService.add({severity:'error', summary:'Error', detail:'Could not generate report'});
        this.displayReportDialog = false;
      }
    });
  }

  openNew() {
    this.newProject = this.getEmptyProject();
    this.isEditing = false;
    this.availableTables = [];
    this.selectedTablesList = [];
    this.displayDialog = true;
  }

  editProject(project: Project) {
    this.newProject = { ...project };
    this.isEditing = true;
    this.selectedTablesList = project.selectedTables
        ? project.selectedTables.split(' ').filter(t => t.length > 0)
        : [];
    this.availableTables = this.selectedTablesList.map(t => ({label: t, value: t}));
    this.displayDialog = true;
  }

  deleteProject(project: Project) {
    this.confirmationService.confirm({
      message: 'Are you sure you want to delete ' + project.name + '?',
      header: 'Confirm',
      icon: 'pi pi-exclamation-triangle',
      accept: () => {
        if (project.id) {
          this.projectService.deleteProject(project.id).subscribe({
            next: () => {
              this.projects = this.projects.filter(val => val.id !== project.id);
              this.messageService.add({severity:'success', summary:'Successful', detail:'Project Deleted', life: 3000});
            },
            error: () => {
              this.messageService.add({severity:'error', summary:'Error', detail:'Could not delete project'});
            }
          });
        }
      }
    });
  }

  saveProject() {
    this.newProject.selectedTables = this.selectedTablesList.join(' ');

    if (this.isEditing && this.newProject.id) {
      // Update existing project
      this.projectService.updateProject(this.newProject.id, this.newProject).subscribe({
        next: (proj) => {
          // Find and update item in the list
          const index = this.projects.findIndex(p => p.id === proj.id);
          if (index !== -1) {
            this.projects[index] = proj;
          }
          this.displayDialog = false;
          this.messageService.add({severity:'success', summary:'Success', detail:'Project Updated'});
        },
        error: (err) => {
          this.messageService.add({severity:'error', summary:'Error', detail:'Failed to update project'});
        }
      });
    } else {
      // Create new project (existing logic)
      this.projectService.createProject(this.newProject).subscribe({
        next: (proj) => {
          this.projects.push(proj);
          this.displayDialog = false;
          this.messageService.add({severity:'success', summary:'Success', detail:'Project Created'});
        },
        error: (err) => {
          this.messageService.add({severity:'error', summary:'Error', detail:'Failed to create project'});
        }
      });
    }
  }

  getEmptyProject(): Project {
    return {
      name: '',
      oracleHost: 'localhost',
      oraclePort: 1521,
      oracleSid: 'XEPDB1',
      oracleUser: '',
      oraclePassword: '',
      oracleConnectionType: 'SERVICE_NAME',
      oracleCustomDsn: '',
      postgresHost: 'localhost',
      postgresPort: 5432,
      postgresDb: 'postgres',
      postgresUser: '',
      postgresPassword: '',
      postgresSslMode: 'disable',
      postgresSearchPath: '',
      tableFilterMode: '',
      selectedTables: '',
      ora2pgConfig: ''
    };
  }

  testOracle() {
    this.projectService.testOracle(this.newProject).subscribe({
      next: (res) => {
        if (res.success) {
          this.messageService.add({severity:'success', summary:'Oracle OK', detail: res.message});
        } else {
          this.messageService.add({severity:'error', summary:'Connection Failed', detail: res.message});
        }
      },
      error: (err) => {
        // This handles actual network errors (backend down, etc.)
        this.messageService.add({severity:'error', summary:'System Error', detail: 'Could not reach server'});
      }
    });
  }

  testPostgres() {
    this.projectService.testPostgres(this.newProject).subscribe({
      next: (res) => {
        if (res.success) {
          this.messageService.add({severity:'success', summary:'Postgres OK', detail: res.message});
        } else {
          this.messageService.add({severity:'error', summary:'Connection Failed', detail: res.message});
        }
      },
      error: (err) => {
        this.messageService.add({severity:'error', summary:'System Error', detail: 'Could not reach server'});
      }
    });
  }

  fetchTables() {
    this.isFetchingTables = true;
    this.projectService.fetchOracleTables(this.newProject).subscribe({
      next: (tables) => {
        this.availableTables = tables.map(t => ({label: t, value: t}));
        this.isFetchingTables = false;
        this.messageService.add({severity:'success', summary:'Tables Loaded', detail: `Found ${tables.length} tables`});
      },
      error: () => {
        this.isFetchingTables = false;
        this.messageService.add({severity:'error', summary:'Fetch Failed', detail: 'Could not retrieve Oracle tables. Check connection details.'});
      }
    });
  }

  // --- Validation ---

  openValidationDialog(project: Project) {
    this.validationProject = project;
    this.schemaValidationOptions.forEach(o => o.selected = o.key !== 'DEFAULTS');
    this.dataValidationOptions.forEach(o => o.selected = o.key === 'ROW_COUNTS');
    this.displayValidationDialog = true;
  }

  hasAnyValidationSelected(): boolean {
    return [...this.schemaValidationOptions, ...this.dataValidationOptions].some(o => o.selected);
  }

  launchValidation() {
    if (!this.validationProject?.id) return;
    const scope = [
      ...this.schemaValidationOptions.filter(o => o.selected).map(o => o.key),
      ...this.dataValidationOptions.filter(o => o.selected).map(o => o.key)
    ];
    this.displayValidationDialog = false;
    this.router.navigate(['/validation', this.validationProject.id], {
      queryParams: { scope: scope.join(',') }
    });
  }

  viewValidationHistory(project: Project) {
    if (!project.id) return;
    this.projectService.getValidationHistory(project.id).subscribe(runs => {
      this.validationHistoryRuns = runs;
      this.displayValidationHistoryDialog = true;
    });
  }

  viewValidationLogs(runId: number) {
    this.projectService.getValidationLogs(runId).subscribe(logs => {
      this.selectedValidationLogs = logs;
      this.displayValidationLogDialog = true;
    });
  }

  viewValidationReport(runId: number) {
    this.projectService.getValidationReport(runId).subscribe({
      next: (report) => {
        this.validationReport = report;
        this.displayValidationReportDialog = true;
      },
      error: () => {
        this.messageService.add({severity:'error', summary:'Error', detail:'Could not load validation report'});
      }
    });
  }

  getValidationStatusSeverity(status: string): 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast' | undefined {
    switch (status) {
      case 'SUCCESS': return 'success';
      case 'PARTIAL': return 'warn';
      case 'FAILED': return 'danger';
      case 'RUNNING': return 'info';
      default: return 'secondary';
    }
  }
}
