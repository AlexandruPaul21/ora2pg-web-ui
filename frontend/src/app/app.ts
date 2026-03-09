import { Component, signal } from '@angular/core';
import {RouterLink, RouterOutlet} from '@angular/router';
import {Button} from 'primeng/button';
import {Tooltip} from 'primeng/tooltip';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Button, RouterLink, Tooltip],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  protected readonly title = signal('frontend');
}
