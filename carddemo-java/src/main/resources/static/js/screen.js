const keys = { F3: 'PF3', F4: 'PF4', F5: 'PF5', F7: 'PF7', F8: 'PF8', F12: 'PF12' };

document.addEventListener('keydown', event => {
  const button = keys[event.key] && document.querySelector(`button[name=aid][value=${keys[event.key]}]`);
  if (button) {
    event.preventDefault();
    button.click();
  }
});
