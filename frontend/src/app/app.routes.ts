import { Routes } from '@angular/router';
import { DashboardComponent } from './pages/dashboard/dashboard';
import { MigrationConsole } from './pages/migration-console/migration-console';
import { ValidationConsole } from './pages/validation-console/validation-console';

export const routes: Routes = [
  { path: '', component: DashboardComponent },
  { path: 'migration/:id', component: MigrationConsole },
  { path: 'validation/:id', component: ValidationConsole }
];
