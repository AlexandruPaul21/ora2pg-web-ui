import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

// Services
import { Project, ProjectService } from '../../services/project.service';

// PrimeNG 21 Standalone Imports
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog'; // Changed from 'dialog' to DialogModule for safety
import { InputTextModule } from 'primeng/inputtext';
import { PasswordModule } from 'primeng/password';
import { ToastModule } from 'primeng/toast';
import { DividerModule } from 'primeng/divider'; // Added Divider
import { MessageService } from 'primeng/api';

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
    DividerModule
  ],
  providers: [MessageService],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss'
})
export class DashboardComponent implements OnInit {
  // ... (The rest of the logic remains the same) ...
  projectService = inject(ProjectService);
  messageService = inject(MessageService);

  projects: Project[] = [];
  displayDialog: boolean = false;

  newProject: Project = this.getEmptyProject();

  ngOnInit() {
    this.loadProjects();
  }

  loadProjects() {
    this.projectService.getProjects().subscribe(data => {
      this.projects = data;
    });
  }

  openNew() {
    this.newProject = this.getEmptyProject();
    this.displayDialog = true;
  }

  saveProject() {
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

  getEmptyProject(): Project {
    return {
      name: '',
      oracleHost: 'localhost', oraclePort: 1521, oracleSid: 'ORCLCDB', oracleUser: 'system',
      postgresHost: 'localhost', postgresPort: 5432, postgresDb: 'postgres', postgresUser: 'postgres'
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
}
