import { Component, Input } from "@angular/core";
import { Game, Check, phaseLabel } from "./model";
import { PlayerCardComponent } from "./player-card";
@Component({
  selector: "app-overlay",
  standalone: true,
  imports: [PlayerCardComponent],
  template: `
    <div
      class="overlay-scene"
      [class.transparent]="game.state.theme.transparent"
      [class.compact]="compact"
      [style.--civilian]="game.state.theme.civilian"
      [style.--mafia]="game.state.theme.mafia"
      [style.--special]="game.state.theme.special"
    >
      <header class="overlay-header">
        <div class="overlay-tournament">
          <span class="overlay-mark">p<span>.</span></span>
          <div>
            <h2>{{ game.tournamentName }}</h2>
            <p>
              Стол {{ game.tableNumber }} <span>/</span> Партия
              {{ game.gameNumber }} <span>/</span>
              {{ phaseLabel(game.state.phase) }} {{ game.state.round }}
            </p>
          </div>
        </div>
        <div class="overlay-events">
          @if (game.state.nominees.length) {
            <div class="overlay-event-row">
              <span>На голосовании</span>
              <div>
                @for (seat of game.state.nominees; track seat) {
                  <b>{{ seat }}</b>
                }
              </div>
            </div>
          }
          @for (by of checkRoles; track by) {
            @if (checks(by).length) {
              <div class="overlay-event-row">
                <span>{{ by === "DON" ? "Дон" : "Шериф" }}</span>
                <div>
                  @for (check of checks(by); track check.id) {
                    <b
                      [class.black-check]="
                        check.result === 'BLACK' || check.result === 'SHERIFF'
                      "
                      [attr.title]="result(check)"
                      >{{ check.seat }}<small>{{ result(check) }}</small></b
                    >
                  }
                </div>
              </div>
            }
          }
        </div>
      </header>
      <div class="overlay-players">
        @for (p of game.state.players; track p.seat) {
          <app-player-card
            [player]="p"
            [state]="game.state"
            [gameId]="game.id"
            [token]="token"
            [publicView]="publicImages"
          />
        }
      </div>
    </div>
  `,
})
export class OverlayComponent {
  @Input({ required: true }) game!: Game;
  @Input() token = "";
  @Input() compact = false;
  @Input() publicImages = false;
  phaseLabel = phaseLabel;
  checkRoles = ["DON", "SHERIFF"] as const;
  checks(by: string) {
    return this.game.state.checks.filter((c) => c.by === by);
  }
  result(c: Check) {
    return (
      {
        RED: "Мирный",
        BLACK: "Мафия",
        SHERIFF: "Шериф",
        NOT_SHERIFF: "Не шериф",
      } as Record<string, string>
    )[c.result];
  }
}
